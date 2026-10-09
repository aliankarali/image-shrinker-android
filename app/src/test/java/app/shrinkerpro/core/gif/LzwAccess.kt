package app.shrinkerpro.core.gif

/** Test-only door into the internal LZW codec. */
object LzwAccess {
    fun encode(data: ByteArray, minCodeSize: Int): ByteArray = Lzw.Encoder().encode(data, minCodeSize)
    fun decode(data: ByteArray, minCodeSize: Int, count: Int): ByteArray = Lzw.decode(data, minCodeSize, count, 0)
}
