package io.github.ghosthack.imageio.windows;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/**
 * Panama (FFM) bindings to the Windows Imaging Component (WIC) via COM vtable dispatch.
 * <p>
 * Provides the native bridge to decode images via WIC, which supports HEIC (with
 * HEVC Video Extensions), AVIF (with AV1 Video Extensions), WEBP, JPEG-XR, DDS,
 * camera RAW, and all standard image formats on Windows 10+.
 * <p>
 * COM vtable dispatch strategy: {@link Linker#downcallHandle(FunctionDescriptor)}
 * (no fixed address) returns a {@link MethodHandle} that takes an extra leading
 * {@link MemorySegment} parameter for the function pointer. At call time we read
 * the function pointer from the COM object's vtable and pass it as the first arg.
 * <p>
 * Requires {@code --enable-native-access=ALL-UNNAMED} at runtime.
 * Only functional on Windows; all entry points return failure gracefully on other OSes.
 */
final class WicNative {

    private WicNative() {}

    // ── OS guard ────────────────────────────────────────────────────────

    static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).startsWith("win");

    // ── HRESULT helpers ─────────────────────────────────────────────────

    private static final int S_OK    = 0;
    // S_FALSE (0x1): COM already initialized on this thread as MTA — usable, don't uninit.
    // RPC_E_CHANGED_MODE (0x80010106): thread is STA — still usable for WIC
    // (WICImagingFactory is ThreadingModel=Both), don't uninit.

    /**
     * Maximum number of pixels (width * height) we are willing to decode.
     * Prevents OOM on malicious/corrupt images declaring huge dimensions.
     * Default: 256 megapixels (e.g. 16384 x 16384).
     */
    static final long MAX_PIXELS = Long.getLong("imageio.native.maxPixels", 256L * 1024 * 1024);

    private static boolean failed(int hr) { return hr < 0; }

    private static void check(int hr, String msg) throws javax.imageio.IIOException {
        if (failed(hr))
            throw new javax.imageio.IIOException(msg + " (HRESULT 0x" + Integer.toHexString(hr) + ")");
    }

    // ── GUID layout (16 bytes: uint32, uint16, uint16, byte[8]) ────────

    private static final StructLayout GUID_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("Data1"),
            ValueLayout.JAVA_SHORT.withName("Data2"),
            ValueLayout.JAVA_SHORT.withName("Data3"),
            MemoryLayout.sequenceLayout(8, ValueLayout.JAVA_BYTE).withName("Data4")
    );

    /**
     * Allocates and populates a GUID in the given arena.
     * <p>
     * The {@code data4} array must have exactly 8 bytes.
     */
    private static MemorySegment guid(Arena arena, int d1, short d2, short d3, byte[] d4) {
        MemorySegment seg = arena.allocate(GUID_LAYOUT);
        seg.set(ValueLayout.JAVA_INT, 0, d1);
        seg.set(ValueLayout.JAVA_SHORT, 4, d2);
        seg.set(ValueLayout.JAVA_SHORT, 6, d3);
        MemorySegment.copy(d4, 0, seg, ValueLayout.JAVA_BYTE, 8, 8);
        return seg;
    }

    // ── Well-known GUIDs (allocated once in global arena) ──────────────

    /** CLSID_WICImagingFactory {CACAF262-9370-4615-A13B-9F5539DA4C0A} */
    private static final MemorySegment CLSID_WIC_FACTORY;

    /** IID_IWICImagingFactory {EC5EC8A9-C395-4314-9C77-54D7A935FF70} */
    private static final MemorySegment IID_WIC_FACTORY;

    /** GUID_WICPixelFormat32bppPBGRA {6FDDC324-4E03-4BFE-B185-3D77768DC910} */
    private static final MemorySegment GUID_PIXEL_FORMAT_PBGRA;

    // ── COM constants ───────────────────────────────────────────────────

    /** COINIT_MULTITHREADED = 0x0 */
    private static final int COINIT_MULTITHREADED = 0x0;

    /** CLSCTX_INPROC_SERVER = 0x1 */
    private static final int CLSCTX_INPROC_SERVER = 0x1;

    /** WICDecodeMetadataCacheOnDemand = 0 */
    private static final int WICDecodeMetadataCacheOnDemand = 0;

    /** WICBitmapDitherTypeNone = 0 */
    private static final int WICBitmapDitherTypeNone = 0;

    /** WICBitmapPaletteTypeCustom = 0 */
    private static final int WICBitmapPaletteTypeCustom = 0;

    // ── PROPVARIANT / WICBitmapTransformOptions ─────────────────────────

    /** PROPVARIANT total size on 64-bit: vt(2) + reserved(6) + union(16) = 24 bytes */
    private static final int PROPVARIANT_SIZE = 24;

    /** VARTYPE VT_UI2 (unsigned 16-bit integer) */
    private static final short VT_UI2 = 18;

    /** WICBitmapTransformOptions enum values */
    private static final int WICBitmapTransformRotate0          = 0x0;
    private static final int WICBitmapTransformRotate90         = 0x1;
    private static final int WICBitmapTransformRotate180        = 0x2;
    private static final int WICBitmapTransformRotate270        = 0x3;
    private static final int WICBitmapTransformFlipHorizontal   = 0x8;
    private static final int WICBitmapTransformFlipVertical     = 0x10;

    // ── DLL loading and ole32 downcalls ─────────────────────────────────

    private static final Linker LINKER = Linker.nativeLinker();

    /**
     * ole32.dll symbols — loaded lazily only on Windows.
     * On non-Windows, these are null and all entry points bail out early.
     */
    private static final SymbolLookup OLE32;
    private static final MethodHandle CoInitializeEx;
    private static final MethodHandle CoCreateInstance;
    private static final MethodHandle CoUninitialize;

    static {
        if (IS_WINDOWS) {
            System.loadLibrary("ole32");
            System.loadLibrary("windowscodecs");
            OLE32 = SymbolLookup.loaderLookup();

            // HRESULT CoInitializeEx(LPVOID pvReserved, DWORD dwCoInit)
            CoInitializeEx = downcallOle32("CoInitializeEx",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

            // HRESULT CoCreateInstance(REFCLSID rclsid, LPUNKNOWN pUnkOuter,
            //     DWORD dwClsContext, REFIID riid, LPVOID *ppv)
            CoCreateInstance = downcallOle32("CoCreateInstance",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // void CoUninitialize(void)
            CoUninitialize = downcallOle32("CoUninitialize",
                    FunctionDescriptor.ofVoid());

            // Allocate constant GUIDs once in the global arena
            Arena global = Arena.global();
            CLSID_WIC_FACTORY = guid(global, 0xCACAF262, (short) 0x9370, (short) 0x4615,
                    new byte[]{(byte) 0xA1, 0x3B, (byte) 0x9F, 0x55, 0x39, (byte) 0xDA, 0x4C, 0x0A});
            IID_WIC_FACTORY = guid(global, 0xEC5EC8A9, (short) 0xC395, (short) 0x4314,
                    new byte[]{(byte) 0x9C, 0x77, 0x54, (byte) 0xD7, (byte) 0xA9, 0x35, (byte) 0xFF, 0x70});
            GUID_PIXEL_FORMAT_PBGRA = guid(global, 0x6FDDC324, (short) 0x4E03, (short) 0x4BFE,
                    new byte[]{(byte) 0xB1, (byte) 0x85, 0x3D, 0x77, 0x76, (byte) 0x8D, (byte) 0xC9, 0x10});
        } else {
            OLE32 = null;
            CoInitializeEx = null;
            CoCreateInstance = null;
            CoUninitialize = null;
            CLSID_WIC_FACTORY = null;
            IID_WIC_FACTORY = null;
            GUID_PIXEL_FORMAT_PBGRA = null;
        }
    }

    private static MethodHandle downcallOle32(String name, FunctionDescriptor fd) {
        MemorySegment addr = OLE32.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("Symbol not found: " + name));
        return LINKER.downcallHandle(addr, fd);
    }

    // ── COM vtable dispatch handles ─────────────────────────────────────
    // Linker.downcallHandle(FunctionDescriptor) — no address — yields a
    // MethodHandle with an extra leading MemorySegment for the function pointer.

    // IUnknown::Release (vtable[2])
    //   ULONG Release([in] IUnknown *this)
    private static final MethodHandle IUnknown_Release = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    // IWICImagingFactory::CreateStream (vtable[14])
    //   HRESULT CreateStream([in] this, [out] IWICStream **ppIWICStream)
    private static final MethodHandle Factory_CreateStream = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // IWICImagingFactory::CreateDecoderFromStream (vtable[4])
    //   HRESULT CreateDecoderFromStream([in] this, [in] IStream *pIStream,
    //       [in] const GUID *pguidVendor, [in] DWORD dwOptions, [out] IWICBitmapDecoder **ppIDecoder)
    private static final MethodHandle Factory_CreateDecoderFromStream = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    // IWICImagingFactory::CreateFormatConverter (vtable[10])
    //   HRESULT CreateFormatConverter([in] this, [out] IWICFormatConverter **ppIFormatConverter)
    private static final MethodHandle Factory_CreateFormatConverter = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // IWICStream::InitializeFromMemory (vtable[16])
    //   HRESULT InitializeFromMemory([in] this, [in] BYTE *pbBuffer, [in] DWORD cbBufferSize)
    private static final MethodHandle Stream_InitializeFromMemory = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    // IWICBitmapDecoder::GetFrame (vtable[13])
    //   HRESULT GetFrame([in] this, [in] UINT index, [out] IWICBitmapFrameDecode **ppIBitmapFrame)
    private static final MethodHandle Decoder_GetFrame = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    // IWICBitmapSource::GetSize (vtable[3])
    //   HRESULT GetSize([in] this, [out] UINT *puiWidth, [out] UINT *puiHeight)
    private static final MethodHandle Source_GetSize = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // IWICBitmapSource::CopyPixels (vtable[7])
    //   HRESULT CopyPixels([in] this, [in] const WICRect *prc, [in] UINT cbStride,
    //       [in] UINT cbBufferSize, [out] BYTE *pbBuffer)
    private static final MethodHandle Source_CopyPixels = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    // IWICFormatConverter::Initialize (vtable[8])
    //   HRESULT Initialize([in] this, [in] IWICBitmapSource *pISource,
    //       [in] REFWICPixelFormatGUID dstFormat, [in] WICBitmapDitherType dither,
    //       [in] IWICPalette *pIPalette, [in] double alphaThresholdPercent,
    //       [in] WICBitmapPaletteType paletteTranslate)
    private static final MethodHandle Converter_Initialize = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE,
                    ValueLayout.JAVA_INT));

    // IWICBitmapFrameDecode::GetMetadataQueryReader (vtable[8])
    //   HRESULT GetMetadataQueryReader([in] this, [out] IWICMetadataQueryReader **ppReader)
    private static final MethodHandle Frame_GetMetadataQueryReader = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // IWICMetadataQueryReader::GetMetadataByName (vtable[5])
    //   HRESULT GetMetadataByName([in] this, [in] LPCWSTR wzName, [in,out] PROPVARIANT *pvarValue)
    private static final MethodHandle MetadataReader_GetMetadataByName = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // IWICImagingFactory::CreateBitmapFlipRotator (vtable[13])
    //   HRESULT CreateBitmapFlipRotator([in] this, [out] IWICBitmapFlipRotator **ppRotator)
    private static final MethodHandle Factory_CreateBitmapFlipRotator = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // IWICBitmapFlipRotator::Initialize (vtable[8])
    //   HRESULT Initialize([in] this, [in] IWICBitmapSource *pISource,
    //       [in] WICBitmapTransformOptions options)
    private static final MethodHandle FlipRotator_Initialize = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    // ── Vtable helpers ──────────────────────────────────────────────────

    /**
     * Reads the function pointer at vtable index {@code idx} from a COM object.
     * <p>
     * COM object layout: the first pointer-sized value at the object's address
     * points to the vtable (an array of function pointers).
     */
    private static MemorySegment vtable(MemorySegment comObj, int idx) {
        // comObj → pointer to vtable → vtable[idx] is a function pointer
        MemorySegment vtablePtr = comObj.reinterpret(ValueLayout.ADDRESS.byteSize())
                .get(ValueLayout.ADDRESS, 0);
        long offset = (long) idx * ValueLayout.ADDRESS.byteSize();
        return vtablePtr.reinterpret((long)(idx + 1) * ValueLayout.ADDRESS.byteSize())
                .get(ValueLayout.ADDRESS, offset);
    }

    /**
     * Calls IUnknown::Release on a non-null COM pointer.
     */
    private static void release(MemorySegment comObj) {
        if (comObj != null && !MemorySegment.NULL.equals(comObj)) {
            try {
                IUnknown_Release.invokeExact(vtable(comObj, 2), comObj);
            } catch (Throwable ignored) { /* invokeExact signature; native release cannot throw */ }
        }
    }

    // ── EXIF orientation helpers ───────────────────────────────────────────

    /**
     * Allocates a null-terminated UTF-16LE wide string (LPCWSTR) in the arena.
     */
    private static MemorySegment wstr(Arena arena, String s) {
        byte[] utf16 = s.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        MemorySegment seg = arena.allocate(utf16.length + 2L); // +2 for null terminator
        MemorySegment.copy(utf16, 0, seg, ValueLayout.JAVA_BYTE, 0, utf16.length);
        seg.set(ValueLayout.JAVA_SHORT, utf16.length, (short) 0); // null terminator
        return seg;
    }

    /**
     * Reads the EXIF orientation tag from a WIC frame's metadata.
     * <p>
     * Tries JPEG path first ({@code /app1/ifd/\{ushort=274\}}), then
     * TIFF/HEIC/AVIF path ({@code /ifd/\{ushort=274\}}).
     *
     * @param arena arena for temporary allocations
     * @param frame the IWICBitmapFrameDecode COM object
     * @return EXIF orientation value (1-8), or 1 if not found / error
     */
    private static int readExifOrientation(Arena arena, MemorySegment frame) {
        MemorySegment reader = MemorySegment.NULL;
        try {
            // Get metadata query reader from frame (vtable[8] on IWICBitmapFrameDecode)
            MemorySegment ppReader = arena.allocate(ValueLayout.ADDRESS);
            int hr = (int) Frame_GetMetadataQueryReader.invokeExact(
                    vtable(frame, 8), frame, ppReader);
            if (failed(hr)) return 1;
            reader = ppReader.get(ValueLayout.ADDRESS, 0);

            // Try JPEG metadata path first, then TIFF/HEIC/AVIF path.
            // Note: PropVariantClear is intentionally not called on the PROPVARIANT.
            // The orientation tag is always VT_UI2 (inline 16-bit value, no heap
            // allocation), so there is nothing to free.  Adding a PropVariantClear
            // downcall for the theoretical case of an unexpected VARTYPE is not
            // worth the extra ole32 binding.
            String[] paths = {"/app1/ifd/{ushort=274}", "/ifd/{ushort=274}"};
            for (String path : paths) {
                MemorySegment propvariant = arena.allocate(PROPVARIANT_SIZE);
                propvariant.fill((byte) 0); // zero-initialize
                MemorySegment wsPath = wstr(arena, path);

                hr = (int) MetadataReader_GetMetadataByName.invokeExact(
                        vtable(reader, 5), reader, wsPath, propvariant);
                if (failed(hr)) continue;

                // Check VARTYPE at offset 0 — we expect VT_UI2
                short vt = propvariant.get(ValueLayout.JAVA_SHORT, 0);
                if (vt == VT_UI2) {
                    int orientation = Short.toUnsignedInt(propvariant.get(ValueLayout.JAVA_SHORT, 8));
                    if (orientation >= 1 && orientation <= 8) {
                        return orientation;
                    }
                }
            }
            return 1; // default: no rotation
        } catch (Throwable t) {
            return 1;
        } finally {
            release(reader);
        }
    }

    /**
     * Maps EXIF orientation (1-8) to WICBitmapTransformOptions flags.
     *
     * @param orientation EXIF orientation value
     * @return WICBitmapTransformOptions bitmask
     */
    private static int exifToWicTransform(int orientation) {
        return switch (orientation) {
            case 2 -> WICBitmapTransformFlipHorizontal;
            case 3 -> WICBitmapTransformRotate180;
            case 4 -> WICBitmapTransformFlipVertical;
            case 5 -> WICBitmapTransformRotate90 | WICBitmapTransformFlipHorizontal;
            case 6 -> WICBitmapTransformRotate90;
            case 7 -> WICBitmapTransformRotate270 | WICBitmapTransformFlipHorizontal;
            case 8 -> WICBitmapTransformRotate270;
            default -> WICBitmapTransformRotate0; // orientation 1 or unknown
        };
    }

    // ── Cached WIC factory ────────────────────────────────────────────────
    // WICImagingFactory is ThreadingModel=Both (thread-safe). We create it
    // once on first access and reuse it for all subsequent calls.  COM must
    // still be initialised on each calling thread (CoInitializeEx is cheap).

    /**
     * Lazy holder — the factory is created exactly once, on first access.
     * A permanent CoInitializeEx on the loading thread keeps the COM
     * library loaded for the lifetime of the JVM.
     */
    private static class FactoryHolder {
        static final MemorySegment INSTANCE = createFactory();

        private static MemorySegment createFactory() {
            try {
                // Permanent COM init on this thread (never balanced by CoUninitialize)
                CoInitializeEx.invokeExact(MemorySegment.NULL, COINIT_MULTITHREADED);
                MemorySegment ppFactory = Arena.global().allocate(ValueLayout.ADDRESS);
                int hr = (int) CoCreateInstance.invokeExact(
                        CLSID_WIC_FACTORY, MemorySegment.NULL,
                        CLSCTX_INPROC_SERVER, IID_WIC_FACTORY, ppFactory);
                if (hr < 0) return MemorySegment.NULL;
                return ppFactory.get(ValueLayout.ADDRESS, 0);
            } catch (Throwable t) {
                return MemorySegment.NULL;
            }
        }
    }

    /** Returns the cached WIC imaging factory (created on first call). */
    private static MemorySegment cachedFactory() {
        return FactoryHolder.INSTANCE;
    }

    // ── Header probe: can WIC decode this data? ─────────────────────────

    /**
     * Probes a header chunk through WIC to check whether Windows can decode the format.
     *
     * @param header first bytes of the image (4 KB is plenty for identification)
     * @param len    number of valid bytes in {@code header}
     * @return {@code true} if WIC recognised the format and can create a decoder
     */
    static boolean canDecode(byte[] header, int len) {
        if (!IS_WINDOWS) return false;

        MemorySegment stream = MemorySegment.NULL;
        MemorySegment decoder = MemorySegment.NULL;

        try (Arena arena = Arena.ofConfined()) {
            int hrInit = (int) CoInitializeEx.invokeExact(MemorySegment.NULL, COINIT_MULTITHREADED);
            boolean weInitialisedCom = (hrInit == S_OK);

            try {
                MemorySegment factory = cachedFactory();
                if (MemorySegment.NULL.equals(factory)) return false;

                // Create IWICStream
                MemorySegment ppStream = arena.allocate(ValueLayout.ADDRESS);
                int hr = (int) Factory_CreateStream.invokeExact(
                        vtable(factory, 14), factory, ppStream);
                if (failed(hr)) return false;
                stream = ppStream.get(ValueLayout.ADDRESS, 0);

                // Initialize from memory
                MemorySegment buf = arena.allocate(len);
                MemorySegment.copy(header, 0, buf, ValueLayout.JAVA_BYTE, 0, len);
                hr = (int) Stream_InitializeFromMemory.invokeExact(
                        vtable(stream, 16), stream, buf, len);
                if (failed(hr)) return false;

                // Try to create a decoder from the stream
                MemorySegment ppDecoder = arena.allocate(ValueLayout.ADDRESS);
                hr = (int) Factory_CreateDecoderFromStream.invokeExact(
                        vtable(factory, 4), factory, stream,
                        MemorySegment.NULL, // no vendor preference
                        WICDecodeMetadataCacheOnDemand, ppDecoder);
                if (failed(hr)) return false;
                decoder = ppDecoder.get(ValueLayout.ADDRESS, 0);

                return true; // WIC can create a decoder for this data
            } finally {
                release(decoder);
                release(stream);
                if (weInitialisedCom) {
                    CoUninitialize.invokeExact();
                }
            }
        } catch (Throwable t) {
            return false;
        }
    }

    // ── Lightweight size query ─────────────────────────────────────────

    /**
     * Returns image dimensions without full pixel decode.
     * <p>
     * Creates a WIC decoder and reads frame size but does not create a format
     * converter or copy pixels — significantly lighter than {@link #decode}.
     *
     * @param imageData the raw image file bytes
     * @return dimensions as {@code [width, height]}
     * @throws javax.imageio.IIOException if the native size query fails or OS is not Windows
     */
    static int[] getSize(byte[] imageData) throws java.io.IOException {
        if (!IS_WINDOWS)
            throw new javax.imageio.IIOException("WIC is only available on Windows");

        MemorySegment stream = MemorySegment.NULL;
        MemorySegment decoder = MemorySegment.NULL;
        MemorySegment frame = MemorySegment.NULL;

        try (Arena arena = Arena.ofConfined()) {
            int hrInit = (int) CoInitializeEx.invokeExact(MemorySegment.NULL, COINIT_MULTITHREADED);
            boolean weInitialisedCom = (hrInit == S_OK);

            try {
                MemorySegment factory = cachedFactory();
                if (MemorySegment.NULL.equals(factory))
                    throw new javax.imageio.IIOException("Failed to create WIC factory");

                MemorySegment ppStream = arena.allocate(ValueLayout.ADDRESS);
                check((int) Factory_CreateStream.invokeExact(
                        vtable(factory, 14), factory, ppStream),
                        "IWICImagingFactory::CreateStream failed");
                stream = ppStream.get(ValueLayout.ADDRESS, 0);

                MemorySegment nativeBuf = arena.allocateFrom(ValueLayout.JAVA_BYTE, imageData);
                check((int) Stream_InitializeFromMemory.invokeExact(
                        vtable(stream, 16), stream, nativeBuf, imageData.length),
                        "IWICStream::InitializeFromMemory failed");

                MemorySegment ppDecoder = arena.allocate(ValueLayout.ADDRESS);
                check((int) Factory_CreateDecoderFromStream.invokeExact(
                        vtable(factory, 4), factory, stream,
                        MemorySegment.NULL, WICDecodeMetadataCacheOnDemand, ppDecoder),
                        "Unsupported image format");
                decoder = ppDecoder.get(ValueLayout.ADDRESS, 0);

                MemorySegment ppFrame = arena.allocate(ValueLayout.ADDRESS);
                check((int) Decoder_GetFrame.invokeExact(
                        vtable(decoder, 13), decoder, 0, ppFrame),
                        "Failed to read image frame");
                frame = ppFrame.get(ValueLayout.ADDRESS, 0);

                // GetSize on the frame — no format converter or pixel copy needed
                MemorySegment pWidth = arena.allocate(ValueLayout.JAVA_INT);
                MemorySegment pHeight = arena.allocate(ValueLayout.JAVA_INT);
                check((int) Source_GetSize.invokeExact(
                        vtable(frame, 3), frame, pWidth, pHeight),
                        "IWICBitmapSource::GetSize failed");
                int w = pWidth.get(ValueLayout.JAVA_INT, 0);
                int h = pHeight.get(ValueLayout.JAVA_INT, 0);
                if (w <= 0 || h <= 0)
                    throw new javax.imageio.IIOException("Invalid image dimensions: " + w + "x" + h);

                // EXIF orientations 5-8 swap width/height (90°/270° rotations)
                int orientation = readExifOrientation(arena, frame);
                if (orientation >= 5 && orientation <= 8) {
                    return new int[]{h, w};
                }
                return new int[]{w, h};
            } finally {
                release(frame);
                release(decoder);
                release(stream);
                if (weInitialisedCom) {
                    CoUninitialize.invokeExact();
                }
            }
        } catch (java.io.IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new javax.imageio.IIOException("Native WIC size query failed", t);
        }
    }

    // ── Public decode entry point ───────────────────────────────────────

    /**
     * Decodes raw image bytes through WIC and returns a {@link BufferedImage}
     * of type {@code TYPE_INT_ARGB_PRE}.
     * <p>
     * The pipeline:
     * <ol>
     *   <li>CoInitializeEx (multithreaded)</li>
     *   <li>Create IWICImagingFactory</li>
     *   <li>Create IWICStream, initialise from memory</li>
     *   <li>CreateDecoderFromStream</li>
     *   <li>GetFrame(0) → IWICBitmapFrameDecode</li>
     *   <li>Read EXIF orientation; if != 1, create IWICBitmapFlipRotator</li>
     *   <li>CreateFormatConverter → Initialize to 32bppPBGRA</li>
     *   <li>GetSize + CopyPixels into Java array</li>
     *   <li>Wrap in BufferedImage(TYPE_INT_ARGB_PRE)</li>
     * </ol>
     * <p>
     * BGRA premultiplied pixels read as little-endian ints produce 0xAARRGGBB,
     * which maps directly to {@code TYPE_INT_ARGB_PRE} — zero conversion overhead.
     *
     * @param imageData the raw image file bytes
     * @return decoded image
     * @throws javax.imageio.IIOException if the native decode fails or OS is not Windows
     */
    static BufferedImage decode(byte[] imageData) throws java.io.IOException {
        if (!IS_WINDOWS)
            throw new javax.imageio.IIOException("WIC decoding is only available on Windows");

        MemorySegment stream = MemorySegment.NULL;
        MemorySegment decoder = MemorySegment.NULL;
        MemorySegment frame = MemorySegment.NULL;
        MemorySegment flipRotator = MemorySegment.NULL;
        MemorySegment converter = MemorySegment.NULL;

        try (Arena arena = Arena.ofConfined()) {
            // 1. COM init
            int hrInit = (int) CoInitializeEx.invokeExact(MemorySegment.NULL, COINIT_MULTITHREADED);
            boolean weInitialisedCom = (hrInit == S_OK);

            try {
                // 2. Cached WIC factory
                MemorySegment factory = cachedFactory();
                if (MemorySegment.NULL.equals(factory))
                    throw new javax.imageio.IIOException("Failed to create WIC factory");

                // 3. Create IWICStream and initialise from memory
                MemorySegment ppStream = arena.allocate(ValueLayout.ADDRESS);
                check((int) Factory_CreateStream.invokeExact(
                        vtable(factory, 14), factory, ppStream),
                        "IWICImagingFactory::CreateStream failed");
                stream = ppStream.get(ValueLayout.ADDRESS, 0);

                MemorySegment nativeBuf = arena.allocateFrom(ValueLayout.JAVA_BYTE, imageData);
                check((int) Stream_InitializeFromMemory.invokeExact(
                        vtable(stream, 16), stream, nativeBuf, imageData.length),
                        "IWICStream::InitializeFromMemory failed");

                // 4. Create decoder from stream
                MemorySegment ppDecoder = arena.allocate(ValueLayout.ADDRESS);
                check((int) Factory_CreateDecoderFromStream.invokeExact(
                        vtable(factory, 4), factory, stream,
                        MemorySegment.NULL, // no vendor preference
                        WICDecodeMetadataCacheOnDemand, ppDecoder),
                        "IWICImagingFactory::CreateDecoderFromStream failed");
                decoder = ppDecoder.get(ValueLayout.ADDRESS, 0);

                // 5. Get frame 0
                MemorySegment ppFrame = arena.allocate(ValueLayout.ADDRESS);
                check((int) Decoder_GetFrame.invokeExact(
                        vtable(decoder, 13), decoder, 0, ppFrame),
                        "IWICBitmapDecoder::GetFrame(0) failed");
                frame = ppFrame.get(ValueLayout.ADDRESS, 0);

                // 5b. Read EXIF orientation and optionally create flip-rotator
                //     Pipeline: frame → [flipRotator] → converter → CopyPixels
                int orientation = readExifOrientation(arena, frame);
                MemorySegment converterSource = frame; // default: feed frame directly to converter

                if (orientation != 1) {
                    int transform = exifToWicTransform(orientation);
                    // Defensive: readExifOrientation constrains to 1-8, so
                    // exifToWicTransform(2-8) never returns Rotate0.  The
                    // guard stays as a safety net against future changes.
                    if (transform != WICBitmapTransformRotate0) {
                        MemorySegment ppFlipRotator = arena.allocate(ValueLayout.ADDRESS);
                        check((int) Factory_CreateBitmapFlipRotator.invokeExact(
                                vtable(factory, 13), factory, ppFlipRotator),
                                "IWICImagingFactory::CreateBitmapFlipRotator failed");
                        flipRotator = ppFlipRotator.get(ValueLayout.ADDRESS, 0);

                        check((int) FlipRotator_Initialize.invokeExact(
                                vtable(flipRotator, 8), flipRotator, frame, transform),
                                "IWICBitmapFlipRotator::Initialize failed");
                        converterSource = flipRotator;
                    }
                }

                // 6. Create format converter and initialise to 32bppPBGRA
                MemorySegment ppConverter = arena.allocate(ValueLayout.ADDRESS);
                check((int) Factory_CreateFormatConverter.invokeExact(
                        vtable(factory, 10), factory, ppConverter),
                        "IWICImagingFactory::CreateFormatConverter failed");
                converter = ppConverter.get(ValueLayout.ADDRESS, 0);

                check((int) Converter_Initialize.invokeExact(
                        vtable(converter, 8), converter, converterSource,
                        GUID_PIXEL_FORMAT_PBGRA,
                        WICBitmapDitherTypeNone,
                        MemorySegment.NULL, // no palette
                        0.0,                // alpha threshold
                        WICBitmapPaletteTypeCustom),
                        "IWICFormatConverter::Initialize failed");

                // 7. Get dimensions (post-rotation)
                MemorySegment pWidth = arena.allocate(ValueLayout.JAVA_INT);
                MemorySegment pHeight = arena.allocate(ValueLayout.JAVA_INT);
                check((int) Source_GetSize.invokeExact(
                        vtable(converter, 3), converter, pWidth, pHeight),
                        "IWICBitmapSource::GetSize failed");
                int w = pWidth.get(ValueLayout.JAVA_INT, 0);
                int h = pHeight.get(ValueLayout.JAVA_INT, 0);
                if (w <= 0 || h <= 0)
                    throw new javax.imageio.IIOException("Invalid image dimensions: " + w + "x" + h);
                long totalPixels = (long) w * h;
                if (totalPixels > MAX_PIXELS)
                    throw new javax.imageio.IIOException(
                            "Image too large: " + w + "x" + h + " (" + totalPixels
                                    + " pixels exceeds limit of " + MAX_PIXELS + ")");

                // 8. Copy pixels (use long arithmetic to avoid int overflow)
                long stride = (long) w * 4;
                long bufSize = stride * h;
                MemorySegment pixelData = arena.allocate(bufSize, 16);
                check((int) Source_CopyPixels.invokeExact(
                        vtable(converter, 7), converter,
                        MemorySegment.NULL, // entire bitmap
                        (int) stride, (int) bufSize, pixelData),
                        "IWICBitmapSource::CopyPixels failed");

                // 9. Build BufferedImage
                // WIC PBGRA (little-endian) → LE int reads as 0xAARRGGBB → TYPE_INT_ARGB_PRE
                BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
                int[] dest = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();
                MemorySegment.copy(pixelData, ValueLayout.JAVA_INT, 0, dest, 0, dest.length);

                return result;
            } finally {
                release(converter);
                release(flipRotator);
                release(frame);
                release(decoder);
                release(stream);
                if (weInitialisedCom) {
                    CoUninitialize.invokeExact();
                }
            }
        } catch (java.io.IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new javax.imageio.IIOException("Native WIC image decode failed", t);
        }
    }
}
