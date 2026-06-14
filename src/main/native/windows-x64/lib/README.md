# Bundled Windows x64 MSVC runtime DLLs

These DLLs make ONNX Runtime (used by the chartsearchai / querystore embedding
provider) load on a clean Windows x64 machine that does **not** have the
Microsoft Visual C++ Redistributable installed.

Without them, the first chartsearchai query on such a machine fails with:

```
java.lang.UnsatisfiedLinkError: ...\onnxruntime.dll: A dynamic link library (DLL)
    initialization routine failed
    at ai.onnxruntime.OrtEnvironment.<clinit>
```

and every subsequent query then dies with
`NoClassDefFoundError: Could not initialize class ai.onnxruntime.OrtEnvironment`.

At startup `WindowsRuntimePatcher.preloadIfNeeded()` pre-loads these by absolute
path into the standalone JVM (which embeds Tomcat), so the Windows loader can
satisfy `onnxruntime.dll`'s imports from the already-resident modules — no matter
where ONNX extracts the DLL. Load order is handled in code (the `vcruntime140`
pair before `msvcp140`, which links against them).

## Bundled files

| File | Bytes |
|------|-------|
| `vcruntime140.dll`   | 124544 |
| `vcruntime140_1.dll` | 49792  |
| `msvcp140.dll`       | 557728 |

These are the **x64** runtime, matching the 64-bit ONNX Runtime DLL. On Windows
10/11 the Universal CRT (`ucrtbase.dll` etc.) these depend on is part of the OS,
so no further redistributable is required.

## Provenance

Extracted from the official **Microsoft Visual C++ 2015–2022 Redistributable
(x64)** installer, `VC_redist.x64.exe` (runtime 14.44.35211, June 2025), downloaded
from https://aka.ms/vs/17/release/vc_redist.x64.exe.

- Installer SHA-256:
  `cc0ff0eb1dc3f5188ae6300faef32bf5beeba4bdd6e8e445a9184072096b713b`
  (matches Microsoft's published CDN hash).
- Each DLL's Authenticode content digest matches Microsoft's embedded signature
  (i.e. the bytes are exactly what Microsoft signed; verified with
  `osslsigncode verify`).

DLL SHA-256:

```
0f885b509a685d2bbfa652fed26b5fb31d88fbdab0a978c641d1c7b8aa460aa9  msvcp140.dll
d5e4d9a3e835fa679450145d6a7d94e36573a509317111904d9b3712c30d9066  vcruntime140.dll
1f2d41c4aa5db0bc33ebf7b66d72943a817d7ce6cbe880502a9403823633093f  vcruntime140_1.dll
```

These DLLs are redistributable under the Visual Studio / MSVC redistributable
license.

## Packaging

The standalone assembly (`src/main/assembly/zip-standalone.xml`) copies all of
`src/main/native/**` into `<install-root>/native/`, so these ship automatically.

`WindowsRuntimePatcher` treats them as a **fallback**: if the machine already has
the Visual C++ Redistributable installed (`%SystemRoot%\System32\msvcp140.dll`
exists), it uses that system copy and does not load these — so a newer system
runtime is never shadowed by this (possibly older) bundled one. The bundle is
injected only when the system runtime is absent. If the DLLs are ever missing
from the build too, the patcher logs a warning and never aborts startup.

## Updating

To refresh to a newer redistributable: download the current `VC_redist.x64.exe`,
verify its SHA-256 against Microsoft's CDN, extract the attached burn cabinet
(its `MSCF` payload), then pull `vcruntime140.dll_amd64`, `vcruntime140_1.dll_amd64`
and `msvcp140.dll_amd64` from the `_amd64` payload cab and drop them here without
the `_amd64` suffix.
