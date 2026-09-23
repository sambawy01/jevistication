# Third-party notices

Source code in this repository that is derived from third-party work, with the notice its licence
requires. Licence checks for every dependency — including the runtime and native libraries whose
attribution is still owed and not yet bundled (build risk 11) — are in
[`docs/LICENSING.md`](docs/LICENSING.md).

---

## river-raid-2k

- **Upstream:** https://github.com/joaoneto/river-raid-2k, commit `5148ace` (read 2026-09-23)
- **Licence:** MIT, from the repository's `LICENSE` file
- **Used in:** `game/src/main/kotlin/dev/loupe/game/Noise.kt` (the gradient-noise function, adapted)
  and the idea behind `RiverGenerator` in `game/src/main/kotlin/dev/loupe/game/River.kt` (bank width
  from the magnitude of noise sampled down the river; an island when the banks leave enough water).
- **Not used:** its sprites, palette, engine, or any other asset. Its player sprite imitates a
  commercial game's silhouette, so the game's sprites were drawn fresh.

```
MIT License

Copyright (c) 2017 João Neto

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
