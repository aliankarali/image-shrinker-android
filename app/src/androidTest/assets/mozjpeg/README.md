Golden files for MozJpegTest: `odd.ppm` (301×203, raw RGB, so no decoder is
involved) and what the macOS app's encoder — mozjpeg 4.1.5 `cjpeg` — makes of
it:

    cjpeg -outfile odd-standard.jpg odd.ppm          # Standard passes no -quality
    cjpeg -quality 40 -outfile odd-q40.jpg odd.ppm   # Super Low
    cjpeg -quality 85 -outfile odd-q85.jpg odd.ppm   # High (4:2:2)
    cjpeg -quality 92 -outfile odd-q92.jpg odd.ppm   # 4:4:4 above 90

The app's encoder must reproduce these byte for byte.
