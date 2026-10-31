# FFmpeg Distribution

The Workshop binary can include FFmpeg as a separate local decoder process. The public source build does not contain an FFmpeg executable; use `-BundleDecoder` only with a separately obtained build after reviewing its license.

The 2.0 Workshop binary uses the unmodified Windows 64-bit static build `9.0.1-full_build-www.gyan.dev` from GyanD/codexffmpeg. It is GPLv3-licensed. The binary package includes the provider's `LICENSE`, build information and the Mod's `FFMPEG-NOTICE.txt`.

The exact FFmpeg source archive for this binary is attached to the `v2.0.0` source release. The build configuration and external-library list are recorded in the accompanying upstream `README.txt`; those external projects retain their own licenses and source-distribution requirements.

Imported music, voice lines and other audio files are not part of this source repository. A user distributing a different bundled decoder must review the license and corresponding-source terms for that exact binary.
