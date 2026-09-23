/*
 * Adapted from river-raid-2k (https://github.com/joaoneto/river-raid-2k), lib.js `Utils.perlin`.
 *
 * MIT License
 *
 * Copyright (c) 2017 João Neto
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package dev.loupe.game

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * 2-D gradient (Perlin) noise, as the prototype uses it to shape the river banks.
 *
 * The structure — a pseudo-random unit gradient per lattice corner, dotted with the offset and
 * blended linearly — is the prototype's (itself the Wikipedia formulation). The corner hash is
 * rewritten: the JavaScript mixed 32-bit shifts with IEEE doubles, which JS truncates in ways that
 * do not carry over to the JVM, so a faithful copy would not have been reproducible anyway. Here it
 * is plain wrapping 32-bit integer arithmetic, which is identical on every JVM and on Android.
 */
internal object Noise {

    fun perlin(x: Double, y: Double): Double {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val x1 = x0 + 1
        val y1 = y0 + 1
        val sx = x - x0
        val sy = y - y0

        val ix0 = lerp(dotGridGradient(x0, y0, sx, sy), dotGridGradient(x1, y0, sx - 1, sy), sx)
        val ix1 = lerp(dotGridGradient(x0, y1, sx, sy - 1), dotGridGradient(x1, y1, sx - 1, sy - 1), sx)
        return lerp(ix0, ix1, sy)
    }

    private fun lerp(a0: Double, a1: Double, w: Double): Double = (a1 - a0) * w + a0

    private fun dotGridGradient(ix: Int, iy: Int, dx: Double, dy: Double): Double {
        val angle = hash(ix, iy) * (2.0 * PI / 4294967296.0)
        return cos(angle) * dx + sin(angle) * dy
    }

    /** A 32-bit mix of the lattice coordinates, unsigned, as a Double in [0, 2^32). */
    private fun hash(ix: Int, iy: Int): Double {
        var h = ix * 0x27d4eb2d xor (iy * 0x165667b1)
        h = h xor (h ushr 15)
        h *= -0x7a143595
        h = h xor (h ushr 13)
        h *= -0x3d4d51cb
        h = h xor (h ushr 16)
        return (h.toLong() and 0xffffffffL).toDouble()
    }
}
