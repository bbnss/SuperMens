# SuperMens 0.3.0 (15): release provenance

The official release uses package `com.bbnss.SuperMens`, version name `0.3.0`
and version code `15`.

`SuperMens.apk` on GitHub is the universal APK downloaded from Google Play's
generated APK API for bundle version 15. Google has already signed it with the
same app-signing certificate used for Play distribution. It is distributed
unchanged; the maintainer does not sign it again with the upload key.

| Artifact | SHA-256 |
| --- | --- |
| AAB uploaded to Play | `8e92be641a7671e672035ec310c01de2e03a003a1da71a9cf97fce56f4110fa1` |
| Universal APK for GitHub | `b433aebd9f038c82c564f9420c95625c0906c9e6258d59c6575ebe1db77d4b1c` |
| Google app-signing certificate | `80ed37dc005ba128fd9b7aad000f55da6612c3c85fb0fc2f1afe60fb39d0f320` |

Play installs device-specific APKs in many cases. Those files need not have the
same whole-file hash as this universal APK; their package, version and signing
identity correspond to the same uploaded bundle.

The source tag for this release is `v0.3.0`. The build originally used a working
tree based on `2d374c1dfd1c2dfd06ad63b85297fb0418728aec`, with additional local
changes. Those changes are included in this release's source.

The recorded SHA-256 of the 168-file build-time source snapshot is
`c6198bdaf90edd705e25a0a558433e46323938a93522dd714bef1dd51e6ac9da`.
The snapshot was reconstructed and verified against that recorded digest.
For each sorted relative path, hash the UTF-8 path, a NUL byte, the file bytes
and another NUL byte, in sequence. The per-file hashes and exact file list are
in [the release record](releases/0.3.0-15.json).

The README, two privacy document headers and this provenance documentation were
updated after the build. Application source, resources, dependencies and build
configuration match the recorded snapshot. The record was assembled after the
original build and is not a signed build attestation. An independently rebuilt
unsigned artifact may differ in build metadata; the download hashes identify
the actual distributed artifacts.

Earlier GitHub releases use `it.supermens.offline` or
`it.supermens.offline.beta`, and their signatures differ. They remain historical
releases and separate installations; this APK does not overwrite their private
archives. Export and import a backup to move data to the current package.
