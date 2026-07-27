# ImageIO routing principles

Status: implemented. This document defines the routing contract used by the
backend service and routing SPI implementation.

## Core principle

The host Java runtime is the baseline. With no imageio-native backend modules
installed, ImageIO behaves exactly as supplied by that runtime.

Adding a backend module is an explicit opt-in to that backend. If exactly one
installed backend can decode an input, that backend owns the input, including
formats that the JDK can also decode.

For example, adding the Windows module means WIC owns every input that WIC can
actually decode. If WIC can decode a JPEG, the JPEG is routed to WIC rather than
the JDK reader. Applications that want the JDK reader for a particular
intersection can explicitly route that format back to the host.

## Principles

1. **Preserve the host by default.** With no added backend capable of decoding
   an input, the routing SPI declines it and the host ImageIO implementation
   proceeds normally.
2. **Module presence is authorization.** Adding a backend module opts into all
   formats that backend can actually decode. There is no implicit
   "supplemental formats only" mode.
3. **A single added backend overrides the JDK.** If one installed backend is
   capable, it owns the input even when a host JDK reader is also capable.
4. **Capability is input-specific.** Declaring support for a format or container
   only makes a backend a candidate. A lightweight probe must confirm that the
   backend on this machine can open the actual input. This accounts for optional
   Windows codecs and for different codecs inside the same video container.
5. **Non-intersecting capability sets do not collide.** A format supported by
   only one added backend belongs to that backend without consulting collision
   policy.
6. **Intersections have one deterministic owner.** When multiple added backends
   are capable, a portable/software-codec backend wins by default over a
   platform-native backend. This is a predictability and portability policy,
   not a claim that one implementation is inherently more secure.
7. **Applications control intersections explicitly.** A small, application-owned
   Java configuration can select a different backend—or the host JDK—for any
   intersecting format.
8. **Routing is not decode-time fallback.** The router filters candidates using
   capability probes, selects exactly one owner, and invokes it once. If the
   selected decoder subsequently fails, its error is returned; the router does
   not silently retry another implementation.
9. **Configuration is stable.** Application routing must be installed before
   the first routed ImageIO operation. The configuration freezes on first use,
   making behavior independent of class-loading or SPI-registration timing.
10. **Direct backend APIs bypass routing.** Calling a WIC, Media Foundation,
    FFmpeg, libvips, or ImageMagick API directly always invokes that backend.

## Ownership algorithm

For an input `x` with detected format `f`:

```text
candidates = installed backends
    that declare f
    and whose lightweight probe accepts x

if candidates is empty:
    decline x                         // host JDK behavior

if policy explicitly selects host for f:
    decline x                         // host JDK behavior

if policy selects backend b for f and b is in candidates:
    select b

if candidates contains exactly one backend:
    select that backend               // overrides the JDK

select the deterministic default:
    portable/software before platform-native
    stable backend ID as the tie-breaker within a class
```

An explicit backend preference only resolves among capable candidates. It never
forces an unavailable backend to decode an unsupported input. Unknown backend
IDs in application configuration should fail fast as configuration errors.

## Examples

The JPEG/FFmpeg combination is intentionally illustrative; it defines the
routing semantics independently of the formats implemented by today's modules.

Assume the host JDK, Windows/WIC, and FFmpeg can all decode JPEG:

| Installed modules | Application rule | JPEG owner |
|---|---|---|
| None | None | Host JDK |
| Windows | None | Windows/WIC |
| FFmpeg | None | FFmpeg |
| Windows + FFmpeg | None | FFmpeg (portable/software default) |
| Windows + FFmpeg | `jpeg -> windows` | Windows/WIC |
| Windows + FFmpeg | `jpeg -> host` | Host JDK |

If the Windows module is installed but WIC's probe rejects a particular JPEG,
WIC is not a candidate for that input. Another capable added backend owns it,
or the router declines it and leaves it to the host.

For non-intersecting formats:

```text
Windows supports HEIC, FFmpeg does not  -> Windows owns HEIC
FFmpeg supports MKV, Windows does not   -> FFmpeg owns MKV
Neither supports a PNG input            -> host ImageIO handles PNG
```

## Application-owned routing file

The application may keep all intersection choices in one minimal Java file:

```java
public final class AppImageIoRouting {
    private AppImageIoRouting() {}

    public static void install() {
        ImageIoRouting.configure(routes -> routes
                .prefer("jpeg", "windows")
                .prefer("heic", "windows")
                .prefer("mkv", "ffmpeg")
                .preferHost("png"));
    }
}
```

The application calls it once during startup, before its first ImageIO
operation:

```java
AppImageIoRouting.install();
```

Configuration is explicit Java code rather than registration-order tricks,
system-property priority lists, or magically discovered application classes.

## Registration model

Backend modules should register backend services, not mutually competing
`ImageReaderSpi` implementations.

One routing SPI per media category owns integration with ImageIO:

```text
ImageIO
    |
    v
routing SPI
    |
    +-- detect the format once
    +-- discover installed backend services
    +-- run lightweight capability probes
    +-- resolve ownership
    `-- delegate to exactly one backend
```

The routing SPI is ordered before host readers, but returns `false` whenever no
added backend owns the input. Consequently it affects a host format only when
an installed backend has positively claimed that particular input.

Backend-to-backend `IIORegistry.setOrdering()`, shared format advertisements on
every backend SPI, global priority properties, and decode-time retry chains are
not part of this design.
