package io.github.ghosthack.imageio.video.windows;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Panama (FFM) bindings to Windows Media Foundation and Shell COM APIs
 * for video frame extraction and metadata retrieval.
 * <p>
 * Provides the native bridge to:
 * <ul>
 *   <li><b>IMFSourceReader</b> — time-based frame extraction via Media Foundation</li>
 *   <li><b>IShellItemImageFactory</b> — quick thumbnail extraction via Shell</li>
 * </ul>
 * <p>
 * COM vtable dispatch strategy: {@link Linker#downcallHandle(FunctionDescriptor)}
 * (no fixed address) returns a {@link MethodHandle} that takes an extra leading
 * {@link MemorySegment} parameter for the function pointer. At call time we read
 * the function pointer from the COM object's vtable and pass it as the first arg.
 * <p>
 * Requires {@code --enable-native-access=ALL-UNNAMED} at runtime.
 * Only functional on Windows; all entry points return failure gracefully on other OSes.
 */
final class WindowsVideoNative {

    private WindowsVideoNative() {}

    // ── OS guard ────────────────────────────────────────────────────────

    static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).startsWith("win");

    // ── HRESULT helpers ─────────────────────────────────────────────────

    static final int S_OK = 0;

    static boolean failed(int hr) { return hr < 0; }

    static void check(int hr, String msg) throws javax.imageio.IIOException {
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
     * The {@code data4} array must have exactly 8 bytes.
     */
    static MemorySegment guid(Arena arena, int d1, short d2, short d3, byte[] d4) {
        MemorySegment seg = arena.allocate(GUID_LAYOUT);
        seg.set(ValueLayout.JAVA_INT, 0, d1);
        seg.set(ValueLayout.JAVA_SHORT, 4, d2);
        seg.set(ValueLayout.JAVA_SHORT, 6, d3);
        MemorySegment.copy(d4, 0, seg, ValueLayout.JAVA_BYTE, 8, 8);
        return seg;
    }

    // ── Vtable helpers ──────────────────────────────────────────────────

    /**
     * Reads the function pointer at vtable index {@code idx} from a COM object.
     * <p>
     * COM object layout: the first pointer-sized value at the object's address
     * points to the vtable (an array of function pointers).
     */
    static MemorySegment vtable(MemorySegment comObj, int idx) {
        MemorySegment vtablePtr = comObj.reinterpret(ValueLayout.ADDRESS.byteSize())
                .get(ValueLayout.ADDRESS, 0);
        long offset = (long) idx * ValueLayout.ADDRESS.byteSize();
        return vtablePtr.reinterpret((long) (idx + 1) * ValueLayout.ADDRESS.byteSize())
                .get(ValueLayout.ADDRESS, offset);
    }

    /**
     * Calls IUnknown::Release on a non-null COM pointer.
     */
    static void release(MemorySegment comObj) {
        if (comObj != null && !MemorySegment.NULL.equals(comObj)) {
            try {
                IUnknown_Release.invokeExact(vtable(comObj, 2), comObj);
            } catch (Throwable ignored) { /* native release cannot throw */ }
        }
    }

    // ── Wide string helper ──────────────────────────────────────────────

    /**
     * Allocates a null-terminated UTF-16LE wide string (LPCWSTR) in the arena.
     */
    static MemorySegment wstr(Arena arena, String s) {
        byte[] utf16 = s.getBytes(StandardCharsets.UTF_16LE);
        MemorySegment seg = arena.allocate(utf16.length + 2L); // +2 for null terminator
        MemorySegment.copy(utf16, 0, seg, ValueLayout.JAVA_BYTE, 0, utf16.length);
        seg.set(ValueLayout.JAVA_SHORT, utf16.length, (short) 0); // null terminator
        return seg;
    }

    // ── Constants ───────────────────────────────────────────────────────

    /** COINIT_MULTITHREADED = 0x0 */
    private static final int COINIT_MULTITHREADED = 0x0;

    /** MF_VERSION = 0x00020070 (Media Foundation version 2.112) */
    static final int MF_VERSION = 0x00020070;

    /** MF_SOURCE_READER_FIRST_VIDEO_STREAM = 0xFFFFFFFC */
    static final int MF_SOURCE_READER_FIRST_VIDEO_STREAM = 0xFFFFFFFC;

    // ── DLL loading and flat downcalls ──────────────────────────────────

    private static final Linker LINKER = Linker.nativeLinker();

    private static final SymbolLookup LOADER_LOOKUP;

    // ole32 flat functions
    private static final MethodHandle CoInitializeEx;
    private static final MethodHandle CoUninitialize;

    // mfplat flat functions
    private static final MethodHandle MFStartup;
    private static final MethodHandle MFShutdown;
    private static final MethodHandle MFCreateMediaType;

    // mfreadwrite flat function
    private static final MethodHandle MFCreateSourceReaderFromURL;

    // shell32 flat function
    private static final MethodHandle SHCreateItemFromParsingName;

    static {
        if (IS_WINDOWS) {
            System.loadLibrary("ole32");
            System.loadLibrary("mfplat");
            System.loadLibrary("mfreadwrite");
            System.loadLibrary("shell32");
            LOADER_LOOKUP = SymbolLookup.loaderLookup();

            // HRESULT CoInitializeEx(LPVOID pvReserved, DWORD dwCoInit)
            CoInitializeEx = downcall("CoInitializeEx",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

            // void CoUninitialize(void)
            CoUninitialize = downcall("CoUninitialize",
                    FunctionDescriptor.ofVoid());

            // HRESULT MFStartup(ULONG Version, DWORD dwFlags)
            MFStartup = downcall("MFStartup",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

            // HRESULT MFShutdown(void)
            MFShutdown = downcall("MFShutdown",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));

            // HRESULT MFCreateMediaType(IMFMediaType **ppMFType)
            MFCreateMediaType = downcall("MFCreateMediaType",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS));

            // HRESULT MFCreateSourceReaderFromURL(LPCWSTR pwszURL,
            //     IMFAttributes *pAttributes, IMFSourceReader **ppSourceReader)
            MFCreateSourceReaderFromURL = downcall("MFCreateSourceReaderFromURL",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // HRESULT SHCreateItemFromParsingName(PCWSTR pszPath,
            //     IBindCtx *pbc, REFIID riid, void **ppv)
            SHCreateItemFromParsingName = downcall("SHCreateItemFromParsingName",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        } else {
            LOADER_LOOKUP = null;
            CoInitializeEx = null;
            CoUninitialize = null;
            MFStartup = null;
            MFShutdown = null;
            MFCreateMediaType = null;
            MFCreateSourceReaderFromURL = null;
            SHCreateItemFromParsingName = null;
        }
    }

    private static MethodHandle downcall(String name, FunctionDescriptor fd) {
        MemorySegment addr = LOADER_LOOKUP.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("Symbol not found: " + name));
        return LINKER.downcallHandle(addr, fd);
    }

    // ── COM vtable dispatch handles ─────────────────────────────────────
    // Linker.downcallHandle(FunctionDescriptor) — no address — yields a
    // MethodHandle with an extra leading MemorySegment for the function pointer.

    // IUnknown::Release (vtable[2])
    //   ULONG Release([in] IUnknown *this)
    static final MethodHandle IUnknown_Release = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    // IMFSourceReader::GetNativeMediaType (vtable[5])
    //   HRESULT GetNativeMediaType([in] this, DWORD dwStreamIndex,
    //       DWORD dwMediaTypeIndex, [out] IMFMediaType **ppMediaType)
    static final MethodHandle IMFSourceReader_GetNativeMediaType = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    // IMFSourceReader::SetCurrentMediaType (vtable[7])
    //   HRESULT SetCurrentMediaType([in] this, DWORD dwStreamIndex,
    //       [in,out] DWORD *pdwReserved, [in] IMFMediaType *pMediaType)
    static final MethodHandle IMFSourceReader_SetCurrentMediaType = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // IMFSourceReader::ReadSample (vtable[8])
    //   HRESULT ReadSample([in] this, DWORD dwStreamIndex, DWORD dwControlFlags,
    //       [out] DWORD *pdwActualStreamIndex, [out] DWORD *pdwStreamFlags,
    //       [out] LONGLONG *pllTimestamp, [out] IMFSample **ppSample)
    static final MethodHandle IMFSourceReader_ReadSample = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // IMFMediaType / IMFAttributes::SetGUID (vtable[24] on IMFAttributes)
    //   HRESULT SetGUID([in] this, REFGUID guidKey, REFGUID guidValue)
    static final MethodHandle IMFMediaType_SetGUID = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // IMFMediaType / IMFAttributes::SetUINT32 (vtable[22] on IMFAttributes)
    //   HRESULT SetUINT32([in] this, REFGUID guidKey, UINT32 unValue)
    static final MethodHandle IMFMediaType_SetUINT32 = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    // IMFMediaType / IMFAttributes::GetUINT64 (vtable[15] on IMFAttributes)
    //   HRESULT GetUINT64([in] this, REFGUID guidKey, [out] UINT64 *punValue)
    static final MethodHandle IMFMediaType_GetUINT64 = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // IMFMediaBuffer::Lock (vtable[3])
    //   HRESULT Lock([in] this, [out] BYTE **ppbBuffer, [out] DWORD *pcbMaxLength,
    //       [out] DWORD *pcbCurrentLength)
    static final MethodHandle IMFMediaBuffer_Lock = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // IMFMediaBuffer::Unlock (vtable[4])
    //   HRESULT Unlock([in] this)
    static final MethodHandle IMFMediaBuffer_Unlock = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    // IMFSample::ConvertToContiguousBuffer (vtable[44] on IMFSample)
    //   HRESULT ConvertToContiguousBuffer([in] this, [out] IMFMediaBuffer **ppBuffer)
    static final MethodHandle IMFSample_ConvertToContiguousBuffer = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // IShellItemImageFactory::GetImage (vtable[3], after IUnknown)
    //   HRESULT GetImage([in] this, SIZE size, SIIGBF flags, [out] HBITMAP *phbm)
    //   SIZE is passed as two ints (cx, cy) on x64 — packed into a LONG64 register
    static final MethodHandle IShellItemImageFactory_GetImage = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    // ── Well-known GUIDs (allocated once in global arena) ──────────────

    /** MF_MT_MAJOR_TYPE {48eba18e-f8c9-4687-bf11-0a74c9f96a08} */
    static final MemorySegment MF_MT_MAJOR_TYPE;

    /** MF_MT_SUBTYPE {f7e34c9a-42e8-4714-b74b-cb29d72c35e5} */
    static final MemorySegment MF_MT_SUBTYPE;

    /** MFMediaType_Video {73646976-0000-0010-8000-00AA00389B71} */
    static final MemorySegment MFMediaType_Video;

    /** MFVideoFormat_RGB32 {00000016-0000-0010-8000-00AA00389B71} */
    static final MemorySegment MFVideoFormat_RGB32;

    /** MF_MT_FRAME_SIZE {1652c33d-d6b2-4012-b834-72030849a37d} */
    static final MemorySegment MF_MT_FRAME_SIZE;

    /** MF_MT_FRAME_RATE {c459a2e8-3d2c-4e44-b132-fee5156c7bb0} */
    static final MemorySegment MF_MT_FRAME_RATE;

    /** MF_PD_DURATION {6c990d33-bb8e-477a-8598-0d5d96fcd88a} */
    static final MemorySegment MF_PD_DURATION;

    /** IID_IShellItemImageFactory {bcc18b79-ba16-442f-80c4-8a59c30c463b} */
    static final MemorySegment IID_IShellItemImageFactory;

    static {
        if (IS_WINDOWS) {
            Arena global = Arena.global();

            // MF_MT_MAJOR_TYPE {48eba18e-f8c9-4687-bf11-0a74c9f96a08}
            MF_MT_MAJOR_TYPE = guid(global, 0x48eba18e, (short) 0xf8c9, (short) 0x4687,
                    new byte[]{(byte) 0xbf, 0x11, 0x0a, 0x74, (byte) 0xc9, (byte) 0xf9, 0x6a, 0x08});

            // MF_MT_SUBTYPE {f7e34c9a-42e8-4714-b74b-cb29d72c35e5}
            MF_MT_SUBTYPE = guid(global, 0xf7e34c9a, (short) 0x42e8, (short) 0x4714,
                    new byte[]{(byte) 0xb7, 0x4b, (byte) 0xcb, 0x29, (byte) 0xd7, 0x2c, 0x35, (byte) 0xe5});

            // MFMediaType_Video {73646976-0000-0010-8000-00AA00389B71}
            MFMediaType_Video = guid(global, 0x73646976, (short) 0x0000, (short) 0x0010,
                    new byte[]{(byte) 0x80, 0x00, 0x00, (byte) 0xAA, 0x00, 0x38, (byte) 0x9B, 0x71});

            // MFVideoFormat_RGB32 {00000016-0000-0010-8000-00AA00389B71}
            MFVideoFormat_RGB32 = guid(global, 0x00000016, (short) 0x0000, (short) 0x0010,
                    new byte[]{(byte) 0x80, 0x00, 0x00, (byte) 0xAA, 0x00, 0x38, (byte) 0x9B, 0x71});

            // MF_MT_FRAME_SIZE {1652c33d-d6b2-4012-b834-72030849a37d}
            MF_MT_FRAME_SIZE = guid(global, 0x1652c33d, (short) 0xd6b2, (short) 0x4012,
                    new byte[]{(byte) 0xb8, 0x34, 0x72, 0x03, 0x08, 0x49, (byte) 0xa3, 0x7d});

            // MF_MT_FRAME_RATE {c459a2e8-3d2c-4e44-b132-fee5156c7bb0}
            MF_MT_FRAME_RATE = guid(global, 0xc459a2e8, (short) 0x3d2c, (short) 0x4e44,
                    new byte[]{(byte) 0xb1, 0x32, (byte) 0xfe, (byte) 0xe5, 0x15, 0x6c, 0x7b, (byte) 0xb0});

            // MF_PD_DURATION {6c990d33-bb8e-477a-8598-0d5d96fcd88a}
            MF_PD_DURATION = guid(global, 0x6c990d33, (short) 0xbb8e, (short) 0x477a,
                    new byte[]{(byte) 0x85, (byte) 0x98, 0x0d, 0x5d, (byte) 0x96, (byte) 0xfc, (byte) 0xd8, (byte) 0x8a});

            // IID_IShellItemImageFactory {bcc18b79-ba16-442f-80c4-8a59c30c463b}
            IID_IShellItemImageFactory = guid(global, 0xbcc18b79, (short) 0xba16, (short) 0x442f,
                    new byte[]{(byte) 0x80, (byte) 0xc4, (byte) 0x8a, 0x59, (byte) 0xc3, 0x0c, 0x46, 0x3b});
        } else {
            MF_MT_MAJOR_TYPE = null;
            MF_MT_SUBTYPE = null;
            MFMediaType_Video = null;
            MFVideoFormat_RGB32 = null;
            MF_MT_FRAME_SIZE = null;
            MF_MT_FRAME_RATE = null;
            MF_PD_DURATION = null;
            IID_IShellItemImageFactory = null;
        }
    }

    // ── COM init / uninit bracket helpers ────────────────────────────────

    /**
     * Initialises COM on the calling thread (multithreaded apartment).
     *
     * @return HRESULT from CoInitializeEx
     * @throws javax.imageio.IIOException if an unexpected failure occurs
     */
    static int comInit() throws javax.imageio.IIOException {
        try {
            return (int) CoInitializeEx.invokeExact(MemorySegment.NULL, COINIT_MULTITHREADED);
        } catch (Throwable t) {
            throw new javax.imageio.IIOException("CoInitializeEx failed", t);
        }
    }

    /**
     * Uninitialises COM on the calling thread.
     * Only call if {@link #comInit()} returned {@link #S_OK}.
     */
    static void comUninit() {
        try {
            CoUninitialize.invokeExact();
        } catch (Throwable ignored) { /* native uninit cannot throw */ }
    }

    /**
     * Starts Media Foundation.
     *
     * @throws javax.imageio.IIOException if MFStartup fails
     */
    static void mfStartup() throws javax.imageio.IIOException {
        try {
            int hr = (int) MFStartup.invokeExact(MF_VERSION, 0);
            check(hr, "MFStartup failed");
        } catch (javax.imageio.IIOException e) {
            throw e;
        } catch (Throwable t) {
            throw new javax.imageio.IIOException("MFStartup failed", t);
        }
    }

    /**
     * Shuts down Media Foundation.
     */
    static void mfShutdown() {
        try {
            MFShutdown.invokeExact();
        } catch (Throwable ignored) { /* native shutdown cannot throw */ }
    }

    // ── Flat function wrappers ──────────────────────────────────────────

    /**
     * Creates an IMFSourceReader from a file URL.
     *
     * @param arena    arena for allocations
     * @param filePath path to the video file (will be converted to wide string)
     * @return the IMFSourceReader COM pointer
     * @throws javax.imageio.IIOException if creation fails
     */
    static MemorySegment createSourceReader(Arena arena, String filePath) throws javax.imageio.IIOException {
        try {
            MemorySegment wpath = wstr(arena, filePath);
            MemorySegment ppReader = arena.allocate(ValueLayout.ADDRESS);
            int hr = (int) MFCreateSourceReaderFromURL.invokeExact(wpath, MemorySegment.NULL, ppReader);
            check(hr, "MFCreateSourceReaderFromURL failed");
            return ppReader.get(ValueLayout.ADDRESS, 0);
        } catch (javax.imageio.IIOException e) {
            throw e;
        } catch (Throwable t) {
            throw new javax.imageio.IIOException("MFCreateSourceReaderFromURL failed", t);
        }
    }

    /**
     * Creates a new empty IMFMediaType.
     *
     * @param arena arena for allocations
     * @return the IMFMediaType COM pointer
     * @throws javax.imageio.IIOException if creation fails
     */
    static MemorySegment createMediaType(Arena arena) throws javax.imageio.IIOException {
        try {
            MemorySegment ppType = arena.allocate(ValueLayout.ADDRESS);
            int hr = (int) MFCreateMediaType.invokeExact(ppType);
            check(hr, "MFCreateMediaType failed");
            return ppType.get(ValueLayout.ADDRESS, 0);
        } catch (javax.imageio.IIOException e) {
            throw e;
        } catch (Throwable t) {
            throw new javax.imageio.IIOException("MFCreateMediaType failed", t);
        }
    }

    /**
     * Creates an IShellItemImageFactory for the given file path.
     *
     * @param arena    arena for allocations
     * @param filePath path to the file
     * @return the IShellItemImageFactory COM pointer
     * @throws javax.imageio.IIOException if creation fails
     */
    static MemorySegment createShellItemImageFactory(Arena arena, String filePath)
            throws javax.imageio.IIOException {
        try {
            MemorySegment wpath = wstr(arena, filePath);
            MemorySegment ppv = arena.allocate(ValueLayout.ADDRESS);
            int hr = (int) SHCreateItemFromParsingName.invokeExact(
                    wpath, MemorySegment.NULL, IID_IShellItemImageFactory, ppv);
            check(hr, "SHCreateItemFromParsingName failed");
            return ppv.get(ValueLayout.ADDRESS, 0);
        } catch (javax.imageio.IIOException e) {
            throw e;
        } catch (Throwable t) {
            throw new javax.imageio.IIOException("SHCreateItemFromParsingName failed", t);
        }
    }
}
