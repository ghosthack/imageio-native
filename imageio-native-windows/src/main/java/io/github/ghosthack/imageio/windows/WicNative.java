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

    private static final int S_OK = 0;

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

    // ── Well-known GUIDs ────────────────────────────────────────────────

    /** Allocate CLSID_WICImagingFactory {CACAF262-9370-4615-A13B-9F5539DA4C0A} */
    private static MemorySegment clsidFactory(Arena arena) {
        return guid(arena, 0xCACAF262, (short) 0x9370, (short) 0x4615,
                new byte[]{(byte) 0xA1, 0x3B, (byte) 0x9F, 0x55, 0x39, (byte) 0xDA, 0x4C, 0x0A});
    }

    /** Allocate IID_IWICImagingFactory {EC5EC8A9-C395-4314-9C77-54D7A935FF70} */
    private static MemorySegment iidFactory(Arena arena) {
        return guid(arena, 0xEC5EC8A9, (short) 0xC395, (short) 0x4314,
                new byte[]{(byte) 0x9C, 0x77, 0x54, (byte) 0xD7, (byte) 0xA9, 0x35, (byte) 0xFF, 0x70});
    }

    /** Allocate GUID_WICPixelFormat32bppPBGRA {6FDDC324-4E03-4BFE-B185-3D77768DC910} */
    private static MemorySegment pixelFormatPBGRA(Arena arena) {
        return guid(arena, 0x6FDDC324, (short) 0x4E03, (short) 0x4BFE,
                new byte[]{(byte) 0xB1, (byte) 0x85, 0x3D, 0x77, 0x76, (byte) 0x8D, (byte) 0xC9, 0x10});
    }

    /** Allocate GUID_VendorMicrosoft {F0E749CA-EDEF-4589-A73A-EE0E626A2B17} */
    private static MemorySegment vendorMicrosoft(Arena arena) {
        return guid(arena, 0xF0E749CA, (short) 0xEDEF, (short) 0x4589,
                new byte[]{(byte) 0xA7, 0x3A, (byte) 0xEE, 0x0E, 0x62, 0x6A, 0x2B, 0x17});
    }

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
        } else {
            OLE32 = null;
            CoInitializeEx = null;
            CoCreateInstance = null;
            CoUninitialize = null;
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
            } catch (Throwable ignored) {}
        }
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

        MemorySegment factory = MemorySegment.NULL;
        MemorySegment stream = MemorySegment.NULL;
        MemorySegment decoder = MemorySegment.NULL;

        try (Arena arena = Arena.ofConfined()) {
            // COM init (may already be initialised — ignore RPC_E_CHANGED_MODE)
            int hrInit = (int) CoInitializeEx.invokeExact(MemorySegment.NULL, COINIT_MULTITHREADED);
            boolean weInitialisedCom = (hrInit == S_OK);

            try {
                // Create WIC factory
                MemorySegment ppFactory = arena.allocate(ValueLayout.ADDRESS);
                int hr = (int) CoCreateInstance.invokeExact(
                        clsidFactory(arena), MemorySegment.NULL,
                        CLSCTX_INPROC_SERVER, iidFactory(arena), ppFactory);
                if (failed(hr)) return false;
                factory = ppFactory.get(ValueLayout.ADDRESS, 0);

                // Create IWICStream
                MemorySegment ppStream = arena.allocate(ValueLayout.ADDRESS);
                hr = (int) Factory_CreateStream.invokeExact(
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
                release(factory);
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

        MemorySegment factory = MemorySegment.NULL;
        MemorySegment stream = MemorySegment.NULL;
        MemorySegment decoder = MemorySegment.NULL;
        MemorySegment frame = MemorySegment.NULL;

        try (Arena arena = Arena.ofConfined()) {
            int hrInit = (int) CoInitializeEx.invokeExact(MemorySegment.NULL, COINIT_MULTITHREADED);
            boolean weInitialisedCom = (hrInit == S_OK);

            try {
                MemorySegment ppFactory = arena.allocate(ValueLayout.ADDRESS);
                check((int) CoCreateInstance.invokeExact(
                        clsidFactory(arena), MemorySegment.NULL,
                        CLSCTX_INPROC_SERVER, iidFactory(arena), ppFactory),
                        "CoCreateInstance(WICImagingFactory) failed");
                factory = ppFactory.get(ValueLayout.ADDRESS, 0);

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

                return new int[]{w, h};
            } finally {
                release(frame);
                release(decoder);
                release(stream);
                release(factory);
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

        MemorySegment factory = MemorySegment.NULL;
        MemorySegment stream = MemorySegment.NULL;
        MemorySegment decoder = MemorySegment.NULL;
        MemorySegment frame = MemorySegment.NULL;
        MemorySegment converter = MemorySegment.NULL;

        try (Arena arena = Arena.ofConfined()) {
            // 1. COM init
            int hrInit = (int) CoInitializeEx.invokeExact(MemorySegment.NULL, COINIT_MULTITHREADED);
            boolean weInitialisedCom = (hrInit == S_OK);

            try {
                // 2. Create WIC factory
                MemorySegment ppFactory = arena.allocate(ValueLayout.ADDRESS);
                check((int) CoCreateInstance.invokeExact(
                        clsidFactory(arena), MemorySegment.NULL,
                        CLSCTX_INPROC_SERVER, iidFactory(arena), ppFactory),
                        "CoCreateInstance(WICImagingFactory) failed");
                factory = ppFactory.get(ValueLayout.ADDRESS, 0);

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

                // 6. Create format converter and initialise to 32bppPBGRA
                MemorySegment ppConverter = arena.allocate(ValueLayout.ADDRESS);
                check((int) Factory_CreateFormatConverter.invokeExact(
                        vtable(factory, 10), factory, ppConverter),
                        "IWICImagingFactory::CreateFormatConverter failed");
                converter = ppConverter.get(ValueLayout.ADDRESS, 0);

                check((int) Converter_Initialize.invokeExact(
                        vtable(converter, 8), converter, frame,
                        pixelFormatPBGRA(arena),
                        WICBitmapDitherTypeNone,
                        MemorySegment.NULL, // no palette
                        0.0,                // alpha threshold
                        WICBitmapPaletteTypeCustom),
                        "IWICFormatConverter::Initialize failed");

                // 7. Get dimensions
                MemorySegment pWidth = arena.allocate(ValueLayout.JAVA_INT);
                MemorySegment pHeight = arena.allocate(ValueLayout.JAVA_INT);
                check((int) Source_GetSize.invokeExact(
                        vtable(converter, 3), converter, pWidth, pHeight),
                        "IWICBitmapSource::GetSize failed");
                int w = pWidth.get(ValueLayout.JAVA_INT, 0);
                int h = pHeight.get(ValueLayout.JAVA_INT, 0);
                if (w <= 0 || h <= 0)
                    throw new javax.imageio.IIOException("Invalid image dimensions: " + w + "x" + h);

                // 8. Copy pixels
                int stride = w * 4;
                int bufSize = stride * h;
                MemorySegment pixelData = arena.allocate(bufSize, 16);
                check((int) Source_CopyPixels.invokeExact(
                        vtable(converter, 7), converter,
                        MemorySegment.NULL, // entire bitmap (no sub-rect)
                        stride, bufSize, pixelData),
                        "IWICBitmapSource::CopyPixels failed");

                // 9. Build BufferedImage
                // WIC PBGRA (little-endian) → LE int reads as 0xAARRGGBB → TYPE_INT_ARGB_PRE
                BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
                int[] dest = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();
                MemorySegment.copy(pixelData, ValueLayout.JAVA_INT, 0, dest, 0, dest.length);

                return result;
            } finally {
                release(converter);
                release(frame);
                release(decoder);
                release(stream);
                release(factory);
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
