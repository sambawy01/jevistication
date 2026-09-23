// Third-party notices for the iOS build of Laya (epic #6 child 3): ONNX Runtime's iOS package and
// the Rust crates in libloupe_tokenizers.a, which :backend-onnx-ios links and the iOS app ships.
//
//   ./gradlew :backend-laya-common:generateIosThirdPartyNotices   rewrite ios-native/THIRD_PARTY_NOTICES-ios.txt
//   ./gradlew :backend-laya-common:checkIosThirdPartyNotices      verify it (part of `check`)
//
// Applied from backend-laya-common/build.gradle.kts, which exists on every host, so a stale notice
// fails `check` on Linux CI too. Offline: reads the reviewed tables and texts under third-party/,
// and ORT's ThirdPartyNotices.txt from the (already resolved) ORT 1.20.0 Maven jar — the same
// release as the iOS package. When ios-native/build.sh has run, it also cross-checks that every
// crate whose source path is embedded in libloupe_tokenizers.a is listed. The iOS app bundles the
// output file; the desktop notices (gradle/third-party-notices.gradle.kts) are unaffected.

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

val iosThirdPartyDir: File = rootProject.file("third-party")
val iosNoticesFile: File = rootProject.file("ios-native/THIRD_PARTY_NOTICES-ios.txt")
val iosRegenerateHint = "Run ./gradlew :backend-laya-common:generateIosThirdPartyNotices and commit the result."
val iosOrtVersion = "1.20.0"

val iosOrtJar = configurations.detachedConfiguration(
    dependencies.create("com.microsoft.onnxruntime:onnxruntime:$iosOrtVersion"),
).apply { isTransitive = false }

fun iosNorm(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n').trimEnd() + "\n"

fun iosSha16(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }.take(16)

fun iosTsv(name: String): List<List<String>> =
    File(iosThirdPartyDir, name).readLines(Charsets.UTF_8)
        .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }

fun buildIosThirdPartyNotices(): String {
    val texts = sortedMapOf<String, String>()
    val usedBy = sortedMapOf<String, MutableSet<String>>()
    fun use(id: String, text: String, user: String) {
        texts[id] = text
        usedBy.getOrPut(id) { sortedSetOf() }.add(user)
    }
    fun stored(h: String, user: String): String {
        val f = File(iosThirdPartyDir, "texts/$h.txt")
        if (!f.isFile) throw GradleException("third-party/texts/$h.txt is missing ($user)")
        use(h, iosNorm(f.readText(Charsets.UTF_8)), user)
        return h
    }
    fun spdx(id: String, user: String): String {
        val f = File(iosThirdPartyDir, "licenses/$id.txt")
        if (!f.isFile) throw GradleException("No canonical text third-party/licenses/$id.txt for SPDX id '$id'")
        use("SPDX:$id", iosNorm(f.readText(Charsets.UTF_8)), user)
        return "SPDX:$id"
    }
    fun spdxIds(expr: String) = Regex("""[A-Za-z0-9.+-]+""").findAll(expr.replace("/", " OR "))
        .map { it.value }.filter { it !in setOf("AND", "OR", "WITH") }.distinct().toList()

    // 1. Native components.
    val nativeOut = StringBuilder()
    val natives = iosTsv("ios-native-components.tsv")
    for ((component, version, licence, copyright, hashes, source) in natives.map { it + List(6 - it.size) { "-" } }.map {
        listOf(it[0], it[1], it[2], it[3], it[4], it[5])
    }.map { Row6(it) }) {
        val ids = if (hashes == "-") spdxIds(licence).map { spdx(it, component) } else hashes.split(',').map { stored(it, component) }
        nativeOut.append(component).append(' ').append(version).append('\n')
        nativeOut.append("  Licence: ").append(licence).append('\n')
        if (copyright != "-") nativeOut.append("  Copyright: ").append(copyright).append('\n')
        nativeOut.append("  Source: ").append(source).append('\n')
        nativeOut.append("  Texts: ").append(ids.joinToString(", ") { "[$it]" }).append("\n\n")
    }
    val ortJar = iosOrtJar.singleFile
    val ortNotices = ZipFile(ortJar).use { z ->
        val e = z.getEntry("ThirdPartyNotices.txt") ?: throw GradleException("ORT $iosOrtVersion jar has no ThirdPartyNotices.txt")
        iosNorm(z.getInputStream(e).readBytes().toString(Charsets.UTF_8))
    }
    val ortId = iosSha16(ortNotices)
    use(ortId, ortNotices, "ONNX Runtime $iosOrtVersion (components it bundles)")
    nativeOut.append("Components bundled inside ONNX Runtime $iosOrtVersion\n")
    nativeOut.append("  Texts: [").append(ortId).append("] (ORT's ThirdPartyNotices.txt for this release)\n\n")

    // 2. Rust crates.
    val crateTsv = iosTsv("ios-tokenizers-crates.tsv")
    val listed = crateTsv.map { "${it[0]} ${it[1]}" }.toSet()
    val refresh = "Run tools/third-party/refresh-tokenizers-crates.py --ios, then regenerate the notices."
    val lock = rootProject.file("ios-native/tokenizers-ffi/Cargo.lock")
    val locked = Regex("""name = "([^"]+)"\s+version = "([^"]+)"""").findAll(lock.readText()).map { "${it.groupValues[1]} ${it.groupValues[2]}" }.toSet()
    val notLocked = listed.filter { it !in locked }
    if (notLocked.isNotEmpty()) throw GradleException("ios-tokenizers-crates.tsv lists crates not in tokenizers-ffi/Cargo.lock: $notLocked. $refresh")
    for (lib in listOf("ios-arm64", "ios-arm64-simulator")) {
        val a = rootProject.file("ios-native/build/LoupeTokenizers.xcframework/$lib/libloupe_tokenizers.a")
        if (!a.isFile) continue
        val text = String(a.readBytes(), Charsets.ISO_8859_1).replace('\\', '/')
        val seen = Regex("""\.cargo/registry/src/[^/]+/([A-Za-z0-9_.+-]+?)-(\d+\.\d+\.\d+[A-Za-z0-9.+-]*)/""")
            .findAll(text).map { "${it.groupValues[1]} ${it.groupValues[2]}" }.toSet()
        val missing = seen.filter { it !in listed }
        if (missing.isNotEmpty()) throw GradleException("libloupe_tokenizers.a ($lib) links crates missing from ios-tokenizers-crates.tsv: $missing. $refresh")
    }
    val crateOut = StringBuilder()
    for (c in crateTsv) {
        val (name, version, licence, origin, hashes) = c
        val ids = if (hashes == "-") spdxIds(licence).map { spdx(it, "crate $name") } else hashes.split(',').map { stored(it, "crate $name") }
        crateOut.append("$name $version  $licence  ($origin)  ").append(ids.joinToString(", ") { "[$it]" }).append('\n')
    }

    // 3. Texts.
    val textOut = StringBuilder()
    for ((tid, text) in texts) {
        textOut.append("=".repeat(78)).append('\n')
        textOut.append("[").append(tid).append("]  used by: ").append(usedBy.getValue(tid).joinToString(", ")).append('\n')
        textOut.append("=".repeat(78)).append("\n\n").append(text).append('\n')
    }

    return buildString {
        append("THIRD-PARTY NOTICES - Loupe on iOS: on-device Laya (ONNX Runtime + tokenizers)\n")
        append("=".repeat(78)).append("\n\n")
        append("The iOS app links the third-party components below through :backend-onnx-ios. Each keeps\n")
        append("its own licence; the texts they require are reproduced in section 3, referenced by [id].\n")
        append("Where a canonical licence text shows a placeholder copyright line, the entry's own\n")
        append("Copyright line applies. Model weights and the Kotlin libraries are covered by the app's\n")
        append("other notices (docs/LICENSING.md).\n\n")
        append("Generated by ./gradlew :backend-laya-common:generateIosThirdPartyNotices from the reviewed\n")
        append("tables in third-party/. Do not edit.\n\n")
        append("1. Native components (").append(natives.size + 1).append(")\n")
        append("2. Rust crates linked into libloupe_tokenizers.a (").append(crateTsv.size).append(")\n")
        append("3. Licence and notice texts (").append(texts.size).append(")\n\n")
        append("\n1. NATIVE COMPONENTS\n--------------------\n\n").append(nativeOut)
        append("\n2. RUST CRATES LINKED INTO LIBLOUPE_TOKENIZERS.A\n-----------------------------------------------\n\n")
        append("From ios-native/tokenizers-ffi/Cargo.lock: crate, version, licence (Cargo.toml), origin, texts.\n\n")
        append(crateOut)
        append("\n\n3. LICENCE AND NOTICE TEXTS\n---------------------------\n\n").append(textOut)
    }
}

class Row6(val v: List<String>) {
    operator fun component1() = v[0]
    operator fun component2() = v[1]
    operator fun component3() = v[2]
    operator fun component4() = v[3]
    operator fun component5() = v[4]
    operator fun component6() = v[5]
}

tasks.register("generateIosThirdPartyNotices") {
    group = "build setup"
    description = "Regenerates ios-native/THIRD_PARTY_NOTICES-ios.txt from the reviewed iOS tables in third-party/."
    doLast {
        val text = buildIosThirdPartyNotices()
        iosNoticesFile.writeText(text, Charsets.UTF_8)
        logger.lifecycle("Wrote ${iosNoticesFile.relativeTo(rootDir)} (${text.length / 1024} KB)")
    }
}

val checkIosThirdPartyNotices = tasks.register("checkIosThirdPartyNotices") {
    group = "verification"
    description = "Fails when ios-native/THIRD_PARTY_NOTICES-ios.txt is missing or stale."
    inputs.dir(iosThirdPartyDir).withPropertyName("thirdParty")
    inputs.file(rootProject.file("ios-native/tokenizers-ffi/Cargo.lock")).withPropertyName("cargoLock")
    inputs.files(iosOrtJar).withPropertyName("ortJar")
    inputs.file(iosNoticesFile).withPropertyName("notices").optional()
    inputs.files(
        rootProject.fileTree("ios-native/build") { include("LoupeTokenizers.xcframework/*/libloupe_tokenizers.a") },
    ).withPropertyName("tokenizerLibs").optional()
    val marker = layout.buildDirectory.file("ios-third-party-notices.ok")
    outputs.file(marker)
    doLast {
        val want = buildIosThirdPartyNotices()
        val problem = when {
            !iosNoticesFile.isFile -> "${iosNoticesFile.relativeTo(rootDir)} is missing"
            iosNoticesFile.readText(Charsets.UTF_8) != want -> "${iosNoticesFile.relativeTo(rootDir)} is stale"
            else -> null
        }
        if (problem != null) throw GradleException("$problem. $iosRegenerateHint")
        marker.get().asFile.writeText("ok\n")
    }
}

tasks.named("check") { dependsOn(checkIosThirdPartyNotices) }
