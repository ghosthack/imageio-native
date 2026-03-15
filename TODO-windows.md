# TODO: Windows Video Backend

Tracking the remaining work to complete `imageio-native-video-windows`.
Once all items are done, flip `WindowsVideoFrameExtractor.isAvailable()`
back to `return IS_WINDOWS;`.

---

## 1. Implement `extractFrame` seek (SetCurrentPosition)

**File**: `WindowsVideoFrameExtractor.java:88-91`

When `time > Duration.ZERO`, seek the source reader before reading a sample.

```
IMFSourceReader::SetCurrentPosition (vtable[6])
  HRESULT SetCurrentPosition(REFGUID guidTimeFormat, REFPROPVARIANT varPosition)
```

Steps:
- Allocate a PROPVARIANT (24 bytes on x64): `vt = VT_I8` (offset 0, short),
  `hVal.QuadPart` (offset 8, long) = `time.toNanos() / 100` (100-ns units).
- Pass `GUID_NULL` (16 zero bytes) as `guidTimeFormat`.
- Call `SetCurrentPosition` on the reader.
- Add `GUID_NULL` to `WindowsVideoNative` global arena.
- Add `IMFSourceReader_SetCurrentPosition` vtable dispatch handle
  to `WindowsVideoNative`.

---

## 2. Implement `extractFrame` ReadSample loop

**File**: `WindowsVideoFrameExtractor.java:93-101`

Read a decoded sample from the source reader after seek.

```
IMFSourceReader::ReadSample (vtable[8]) — handle already exists
  HRESULT ReadSample(DWORD dwStreamIndex, DWORD dwControlFlags,
      DWORD *pdwActualStreamIndex, DWORD *pdwStreamFlags,
      LONGLONG *pllTimestamp, IMFSample **ppSample)
```

Steps:
- Allocate out-params: `pActualIndex` (int), `pFlags` (int),
  `pTimestamp` (long), `ppSample` (pointer).
- Call `ReadSample` with `MF_SOURCE_READER_FIRST_VIDEO_STREAM`, flags = 0.
- Loop until `ppSample` is non-NULL (MF may return NULL samples for
  stream gaps or format changes — check `MF_SOURCE_READERF_ENDOFSTREAM`
  in `pFlags` to break).
- On the sample, call `ConvertToContiguousBuffer` (vtable[44], handle
  already exists) to get an `IMFMediaBuffer`.
- `Lock` the buffer (vtable[3], handle exists) to get the raw pixel pointer
  and current length.
- Copy pixels (see task 3).
- `Unlock` the buffer (vtable[4], handle exists).
- Release sample and buffer COM pointers in finally blocks.

---

## 3. Implement pixel copy: RGB32 buffer to BufferedImage

**File**: `WindowsVideoFrameExtractor.java:103-106`

Convert MF's RGB32 (BGRX, bottom-up) pixel data to a Java `BufferedImage`.

Steps:
- Get the actual output media type from the reader after `SetCurrentMediaType`:
  ```
  IMFSourceReader::GetCurrentMediaType (vtable[6]? verify index)
  ```
  or read `MF_MT_FRAME_SIZE` from the output type after ReadSample.
- Parse `MF_MT_FRAME_SIZE` (UINT64: `width << 32 | height`).
  The `GetUINT64` handle already exists.
- Calculate stride: `width * 4` (RGB32 = 4 bytes/pixel).
  Or read `MF_MT_DEFAULT_STRIDE` for the real stride (may include padding).
- MF RGB32 pixel layout: each pixel is 4 bytes `[B, G, R, X]` in memory
  (little-endian int reads as `0xXXRRGGBB`). Scanlines are bottom-up.
- Allocate `BufferedImage(w, h, TYPE_INT_ARGB_PRE)`.
- Copy row-by-row in reverse (bottom-up → top-down), setting alpha to 0xFF
  since RGB32 has no alpha (X channel is undefined).
- Alternatively, use `TYPE_INT_BGR` if alpha isn't needed, but `TYPE_INT_ARGB_PRE`
  is consistent with the rest of the library.

---

## 4. Implement `getInfo` duration retrieval

**File**: `WindowsVideoFrameExtractor.java:184-188`

Currently returns `Duration.ZERO`.

```
IMFSourceReader::GetPresentationAttribute (vtable[9])
  HRESULT GetPresentationAttribute(DWORD dwStreamIndex,
      REFGUID guidAttribute, PROPVARIANT *pvarAttribute)
```

Steps:
- Add `IMFSourceReader_GetPresentationAttribute` vtable dispatch handle
  to `WindowsVideoNative`.
- Add `MF_SOURCE_READER_MEDIASOURCE` constant (`0xFFFFFFFF`).
- Allocate a PROPVARIANT (24 bytes), zero-initialize.
- Call `GetPresentationAttribute(MF_SOURCE_READER_MEDIASOURCE,
  MF_PD_DURATION, &propvar)`.
  `MF_PD_DURATION` GUID already exists in `WindowsVideoNative`.
- Read `vt` (offset 0, short) — expect `VT_UI8` (21).
- Read `uhVal.QuadPart` (offset 8, long) — duration in 100-ns units.
- Convert: `Duration.ofNanos(value * 100)`.
- Call `PropVariantClear` on the PROPVARIANT (or skip for VT_UI8 since
  it's an inline value with no heap allocation — same rationale as
  `WicNative` EXIF orientation).

---

## 5. Implement `getInfo` codec identification

**File**: `WindowsVideoFrameExtractor.java:190-192`

Currently returns `null`.

```
IMFMediaType / IMFAttributes::GetGUID (vtable[13])
  HRESULT GetGUID(REFGUID guidKey, GUID *pguidValue)
```

Steps:
- Add `IMFMediaType_GetGUID` vtable dispatch handle to `WindowsVideoNative`.
- Call `GetGUID(MF_MT_SUBTYPE, &guid)` on the native media type
  (already obtained in `getInfo`).
- Compare the returned GUID against known FourCC subtype GUIDs:

  | GUID Data1   | Codec string |
  |--------------|-------------|
  | `0x34363248` | `"h264"`    |
  | `0x43564548` | `"hevc"`    |
  | `0x31305641` | `"av1"`     |
  | `0x30385056` | `"vp8"`     |
  | `0x30395056` | `"vp9"`     |
  | `0x3253504D` | `"mpeg2"`   |
  | `0x3153504D` | `"mpeg1"`   |
  | `0x34504D46` | `"mpeg4"`   |
  | `0x31564D57` | `"wmv1"`    |
  | `0x32564D57` | `"wmv2"`    |
  | `0x33564D57` | `"wmv3"`    |

  All MF video subtypes share the same Data2-Data4 as `MFMediaType_Video`
  (`0000-0010-8000-00AA00389B71`), so only Data1 needs comparing.
- Return the matched string, or `null` if unrecognised.

---

## 6. Add missing vtable handles to WindowsVideoNative

Summary of new handles and constants needed by tasks above:

```java
// IMFSourceReader::SetCurrentPosition (vtable[6])
static final MethodHandle IMFSourceReader_SetCurrentPosition = LINKER.downcallHandle(
        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

// IMFSourceReader::GetPresentationAttribute (vtable[9])
static final MethodHandle IMFSourceReader_GetPresentationAttribute = LINKER.downcallHandle(
        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS));

// IMFAttributes::GetGUID (vtable[13])
static final MethodHandle IMFMediaType_GetGUID = LINKER.downcallHandle(
        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

// Constants
static final int MF_SOURCE_READER_MEDIASOURCE = 0xFFFFFFFF;
static final MemorySegment GUID_NULL; // 16 zero bytes in global arena
```

---

## 7. ~~Fix test: `isAvailableOnWindows` now fails~~ DONE

Fixed in commit e818fe3:
- `isAvailableOnWindows`: changed to `assertFalse`
- `extractFrameNullPathThrows` / `getInfoNullPathThrows`: changed from
  `NullPointerException` to `Exception` (null path now reaches WIC decoder
  which wraps it in `IIOException`)

---

## 8. Re-enable the backend

Once tasks 1-6 are done:

- `WindowsVideoFrameExtractor.isAvailable()`: change to `return IS_WINDOWS;`
- `WindowsVideoFrameExtractorTest.isAvailableOnWindows()`: restore `assertTrue`
- `extractFrameNullPathThrows`: restore to `NullPointerException` (add an
  explicit null check in `extractFrame` before reaching the WIC decoder)
- `getInfoNullPathThrows`: restore to `NullPointerException` (add an
  explicit null check in `getInfo` before reaching the WIC decoder)
- Verify all 8 tests pass on a Windows machine or Windows CI
