# mozjpeg 4.1.5

`mozjpeg-4.1.5.tar.gz` is the unmodified release tarball from
<https://github.com/mozilla/mozjpeg/archive/refs/tags/v4.1.5.tar.gz>
(SHA-256 `9fcbb7171f6ac383f5b391175d6fb3acde5e64c4c4727274eade84ed0998fcc1`),
the version the macOS app bundles. `app/src/main/cpp/CMakeLists.txt` refuses to
build if the hash doesn't match.

Licences: IJG, Modified (3-clause) BSD and zlib — see `LICENSE.md`,
`README.ijg` and `README.md` inside the tarball. mozjpeg is used unmodified;
the app's own glue (`app/src/main/cpp/`) only calls its public API.
