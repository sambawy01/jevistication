// Third-party notices (build risk 11): generates the notices file that ships inside the desktop
// app jar, and fails `check` when it is stale.
//
//   ./gradlew :loupe-desktop:generateThirdPartyNotices   rewrite the notices and the lock
//   ./gradlew :loupe-desktop:checkThirdPartyNotices      verify them (part of `check`)
//
// Applied from loupe-desktop/build.gradle.kts. Walks loupe-desktop's runtimeClasspath, which is the
// union of every shipping module's (:engine, :backend-onnx, :sources-desktop, :templates,
// :loupe-desktop, and :game-desktop which the app embeds); test configurations are never read.
// Offline: it reads only jars and POMs Gradle has already resolved, and the reviewed tables and
// texts committed under third-party/. The one networked step, refreshing the Rust crate list, is
// tools/third-party/refresh-tokenizers-crates.py and is run by hand. See docs/LICENSING.md.

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import org.w3c.dom.Element

val thirdPartyDir: File = rootProject.file("third-party")
val noticesFile: File = file("src/main/resources/THIRD_PARTY_NOTICES.txt")
val lockFile: File = File(thirdPartyDir, "notices.lock")
val regenerateHint = "Run ./gradlew :loupe-desktop:generateThirdPartyNotices and commit the result."

/** Strips an OS/arch suffix so a Linux CI and a Mac produce the same file. */
fun normaliseName(name: String): String =
    name.replace(Regex("-(macos|linux|windows)-(arm64|x64)$"), "-<platform>")

fun normaliseText(text: String): String =
    text.replace("\r\n", "\n").replace('\r', '\n').trimEnd() + "\n"

fun sha16(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }.take(16)

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun readTsv(name: String): List<List<String>> =
    File(thirdPartyDir, name).readLines(Charsets.UTF_8)
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.split('\t') }

val noticeEntry = Regex("""(?i)^(licen[cs]e|notice|copying|thirdpartynotices|third[-_]party[-_]notices)([.-][^/]*)?$""")
val nativeEntry = Regex("""(?i)\.(so|dylib|dll|jnilib)$""")

/** Rust crates whose source paths are embedded in a native library (panic locations). */
fun cratesInBinary(bytes: ByteArray): Set<String> {
    val text = String(bytes, Charsets.ISO_8859_1).replace('\\', '/')
    return Regex("""\.cargo/registry/src/[^/]+/([A-Za-z0-9_.+-]+?)-(\d+\.\d+\.\d+[A-Za-z0-9.+-]*)/""")
        .findAll(text).map { "${it.groupValues[1]} ${it.groupValues[2]}" }.toSet()
}

data class Pom(val licences: List<Pair<String, String>>, val organisation: String?, val via: String)

fun pomFile(g: String, a: String, v: String): File? =
    dependencies.createArtifactResolutionQuery().forModule(g, a, v)
        .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java).execute()
        .resolvedComponents.flatMap { it.getArtifacts(MavenPomArtifact::class.java) }
        .filterIsInstance<ResolvedArtifactResult>().firstOrNull()?.file

fun child(e: Element, tag: String): Element? =
    (0 until e.childNodes.length).map { e.childNodes.item(it) }
        .filterIsInstance<Element>().firstOrNull { it.tagName == tag }

/** Reads <licenses>, following <parent> until a POM declares some. */
fun readPom(g: String, a: String, v: String, depth: Int = 0): Pom {
    val f = pomFile(g, a, v) ?: throw GradleException("No POM resolved for $g:$a:$v")
    val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).documentElement
    val org = child(root, "organization")?.let { child(it, "name") }?.textContent?.trim()
    val lic = child(root, "licenses")?.let { ls ->
        (0 until ls.childNodes.length).map { ls.childNodes.item(it) }.filterIsInstance<Element>()
            .map { (child(it, "name")?.textContent?.trim() ?: "") to (child(it, "url")?.textContent?.trim() ?: "") }
    }.orEmpty()
    if (lic.isNotEmpty()) return Pom(lic, org, if (depth == 0) "own POM" else "$g:$a:$v")
    val parent = child(root, "parent") ?: throw GradleException("No <licenses> in the POM chain of $g:$a:$v")
    if (depth > 8) throw GradleException("POM parent chain too deep at $g:$a:$v")
    val p = readPom(
        child(parent, "groupId")!!.textContent.trim(), child(parent, "artifactId")!!.textContent.trim(),
        child(parent, "version")!!.textContent.trim(), depth + 1,
    )
    return p.copy(organisation = org ?: p.organisation)
}

fun spdxOf(name: String, url: String): String? {
    val n = name.lowercase().trim()
    val u = url.lowercase()
    return when {
        ("apache" in n && "2" in n) || "apache.org/licenses/license-2.0" in u -> "Apache-2.0"
        n == "mit" || n == "mit license" || n == "the mit license" || "opensource.org/licenses/mit" in u -> "MIT"
        "bsd 3-clause" in n || "bsd-3-clause" in n || "bsd-3-clause" in u -> "BSD-3-Clause"
        "lgpl" in n || "lgpl" in u -> "LGPL-2.1-or-later"
        else -> null
    }
}

val spdxTokens = Regex("""[A-Za-z0-9.+-]+""")
val spdxOperators = setOf("AND", "OR", "WITH")

/** Licence ids in an SPDX expression (Cargo's older "MIT/Apache-2.0" too) that have a text here. */
fun spdxIds(expr: String): List<String> =
    spdxTokens.findAll(expr.replace("/", " OR ")).map { it.value }
        .filter { it !in spdxOperators }.distinct().toList()

fun canonical(id: String): String {
    val f = File(thirdPartyDir, "licenses/$id.txt")
    if (!f.isFile) throw GradleException("No canonical text third-party/licenses/$id.txt for SPDX id '$id'")
    return normaliseText(f.readText(Charsets.UTF_8))
}

/** Builds the notices text and the lock. Pure function of resolved artifacts and third-party/. */
fun buildThirdPartyNotices(): Pair<String, String> {
    val texts = sortedMapOf<String, String>()           // id -> text
    val usedBy = sortedMapOf<String, MutableSet<String>>() // id -> users
    fun useText(id: String, text: String, user: String) {
        texts[id] = text
        usedBy.getOrPut(id) { sortedSetOf() }.add(user)
    }
    fun useSpdx(id: String, user: String): String {
        val tid = "SPDX:$id"
        useText(tid, canonical(id), user)
        return tid
    }
    val lock = mutableListOf<String>()

    val overrides = readTsv("maven-overrides.tsv").associateBy { it[0] }
    val natives = readTsv("native-components.tsv").groupBy { it[0] }

    // 1. Java libraries.
    val artifacts = configurations.getByName("runtimeClasspath").incoming.artifactView { lenient(false) }
        .artifacts.artifacts.filter { it.id.componentIdentifier is ModuleComponentIdentifier }
    val maven = StringBuilder()
    val nativeCoords = sortedSetOf<String>()
    var tokenizers: Pair<String, File>? = null
    val rows = artifacts.map { art ->
        val id = art.id.componentIdentifier as ModuleComponentIdentifier
        Triple(id, "${id.group}:${normaliseName(id.module)}:${id.version}", art.file)
    }.sortedBy { it.second }
    for ((id, coord, jar) in rows) {
        val ga = "${id.group}:${id.module}"
        val gaNorm = "${id.group}:${normaliseName(id.module)}"
        val pom = readPom(id.group, id.module, id.version)
        val declared = pom.licences.map { (n, u) ->
            spdxOf(n, u) ?: throw GradleException(
                "Unrecognised licence '$n' ($u) in the POM of $ga:${id.version}. Review it, then add a " +
                    "mapping to spdxOf() in gradle/third-party-notices.gradle.kts.",
            )
        }.distinct()
        val ov = overrides[gaNorm] ?: overrides[ga]
        val chosen = ov?.get(1)?.takeIf { it != "-" }
        val licence = when {
            chosen != null -> {
                if (chosen !in declared) throw GradleException("Override for $ga says $chosen, POM says $declared")
                chosen
            }
            declared.size == 1 -> declared.single()
            else -> throw GradleException(
                "$ga:${id.version} declares several licences $declared; record the choice in third-party/maven-overrides.tsv",
            )
        }
        val copyright = ov?.get(2)?.takeIf { it != "-" }
        val entries = mutableListOf<Pair<String, String>>() // entry name -> text id
        var hasLicenceFile = false
        ZipFile(jar).use { z ->
            val names = z.entries().asSequence().filter { !it.isDirectory }.map { it.name }.sorted().toList()
            for (n in names) {
                val base = n.substringAfterLast('/')
                if (base.endsWith(".class") || !noticeEntry.matches(base)) continue
                val text = normaliseText(z.getInputStream(z.getEntry(n)).readBytes().toString(Charsets.UTF_8))
                val tid = sha16(text)
                useText(tid, text, coord)
                entries += n to tid
                if (base.lowercase().startsWith("licen") || base.lowercase().startsWith("copying")) hasLicenceFile = true
            }
            if (names.any { nativeEntry.containsMatchIn(it) }) nativeCoords += coord
            if (ga == "ai.djl.huggingface:tokenizers") tokenizers = id.version to jar
        }
        if (!hasLicenceFile) entries += "(no licence file in the jar; canonical text)" to useSpdx(licence, coord)
        maven.append(coord).append('\n')
        maven.append("  Licence: ").append(licence)
        maven.append("  (POM: ").append(pom.licences.joinToString(" / ") { it.first }).append(", ").append(pom.via).append(")\n")
        if (copyright != null) maven.append("  Copyright: ").append(copyright).append('\n')
        else if (pom.organisation != null) maven.append("  Organisation (POM): ").append(pom.organisation).append('\n')
        ov?.get(3)?.let { maven.append("  Note: ").append(it).append('\n') }
        for ((n, tid) in entries) maven.append("  Text [").append(tid).append("]: ").append(n).append('\n')
        maven.append('\n')
        lock += "maven\t$coord\t$licence\t" + entries.joinToString(",") { "${it.first}=${it.second}" }
    }

    // 2. Native code inside those jars.
    val unreviewed = nativeCoords.filter { it !in natives }
    if (unreviewed.isNotEmpty()) throw GradleException(
        "Jars carry native libraries with no reviewed row in third-party/native-components.tsv: $unreviewed. " +
            "Review what each binary links (docs/LICENSING.md) and add rows for these exact versions.",
    )
    val stale = natives.keys.filter { it !in rows.map { r -> r.second } }
    if (stale.isNotEmpty()) throw GradleException(
        "third-party/native-components.tsv has rows for artifacts that no longer resolve: $stale. Update the table.",
    )
    val nativeOut = StringBuilder()
    for ((coord, comps) in natives.toSortedMap()) for (c in comps) {
        val (_, component, licence, copyright, source) = c
        val ids = if (licence == "-") listOf("see the jar's own notices in section 1") else spdxIds(licence).map { useSpdx(it, "$coord: $component") }
        nativeOut.append(component).append("  (in ").append(coord).append(")\n")
        nativeOut.append("  Licence: ").append(if (licence == "-") "as the jar's own notices" else licence).append('\n')
        if (copyright != "-") nativeOut.append("  Copyright: ").append(copyright).append('\n')
        nativeOut.append("  Source: ").append(source).append('\n')
        nativeOut.append("  Texts: ").append(ids.joinToString(", ") { "[$it]" }).append("\n\n")
        lock += "native\t$coord\t$component\t$licence"
    }

    // 3. Rust crates in libtokenizers.
    val crateTsv = File(thirdPartyDir, "tokenizers-crates.tsv").readLines(Charsets.UTF_8)
    val crates = crateTsv.filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
    val (djlVersion, djlJar) = tokenizers ?: throw GradleException("ai.djl.huggingface:tokenizers is not on the runtime classpath")
    val pinnedDjl = Regex("""tokenizers:(\S+)""").find(crateTsv.first())?.groupValues?.get(1)
    val refreshHint = "Run tools/third-party/refresh-tokenizers-crates.py $djlVersion, then regenerate the notices."
    if (pinnedDjl != djlVersion) throw GradleException(
        "third-party/tokenizers-crates.tsv was generated for DJL $pinnedDjl but $djlVersion resolves. $refreshHint",
    )
    val observed = sortedSetOf<String>()
    ZipFile(djlJar).use { z ->
        val props = z.getEntry("native/lib/tokenizers.properties")?.let { z.getInputStream(it).readBytes().toString(Charsets.UTF_8) }.orEmpty()
        val rustVersion = Regex("""version=([0-9.]+)-""").find(props)?.groupValues?.get(1)
        val pinnedRust = crates.firstOrNull { it[0] == "tokenizers" }?.get(1)
        if (rustVersion == null || pinnedRust == null || rustVersion.split('.').take(2) != pinnedRust.split('.').take(2)) {
            throw GradleException("The DJL jar says Rust tokenizers $rustVersion; the crate list pins $pinnedRust. $refreshHint")
        }
        z.entries().asSequence().filter { nativeEntry.containsMatchIn(it.name) && "tokenizers" in it.name }
            .forEach { observed += cratesInBinary(z.getInputStream(it).readBytes()) }
    }
    val listed = crates.map { "${it[0]} ${it[1]}" }.toSet()
    val missing = observed.filter { it !in listed }
    if (missing.isNotEmpty()) throw GradleException("libtokenizers links crates missing from tokenizers-crates.tsv: $missing. $refreshHint")
    val crateOut = StringBuilder()
    for (c in crates) {
        val (name, version, licence, origin, hashes) = c
        val ids = if (hashes == "-") spdxIds(licence).map { useSpdx(it, "crate $name") } else hashes.split(',').map { h ->
            val f = File(thirdPartyDir, "texts/$h.txt")
            if (!f.isFile) throw GradleException("third-party/texts/$h.txt is missing (crate $name). $refreshHint")
            useText(h, normaliseText(f.readText(Charsets.UTF_8)), "crate $name")
            h
        }
        crateOut.append("$name $version  $licence  ($origin)  ").append(ids.joinToString(", ") { "[$it]" }).append('\n')
        lock += "crate\t$name\t$version\t$licence\t$hashes"
    }

    // 4. Models, data and derived source.
    val assetOut = StringBuilder()
    for (a in readTsv("assets.tsv")) {
        val (item, licence, copyright, where, note) = a
        val ids = spdxIds(licence).map { useSpdx(it, item) }
        assetOut.append(item).append('\n')
        assetOut.append("  Licence: ").append(licence).append('\n')
        assetOut.append("  Copyright: ").append(copyright).append('\n')
        assetOut.append("  Where: ").append(where).append('\n')
        assetOut.append("  Note: ").append(note).append('\n')
        assetOut.append("  Texts: ").append(ids.joinToString(", ") { "[$it]" }).append("\n\n")
        lock += "asset\t$item\t$licence"
    }

    // 5. Texts.
    val textOut = StringBuilder()
    for ((tid, text) in texts) {
        textOut.append("=".repeat(78)).append('\n')
        textOut.append("[").append(tid).append("]  used by: ").append(usedBy.getValue(tid).joinToString(", ")).append('\n')
        textOut.append("=".repeat(78)).append("\n\n").append(text).append('\n')
        lock += "text\t$tid\t${sha256(text.toByteArray(Charsets.UTF_8))}"
    }

    val out = StringBuilder()
    out.append("THIRD-PARTY NOTICES - Loupe desktop app\n")
    out.append("=".repeat(39)).append("\n\n")
    out.append("This software includes the third-party components listed below. Each keeps its own licence;\n")
    out.append("the licence and notice texts they require are reproduced in section 5, referenced by [id].\n")
    out.append("Where a canonical licence text shows a placeholder copyright line, the entry's own\n")
    out.append("Copyright line applies.\n\n")
    out.append("Generated by ./gradlew :loupe-desktop:generateThirdPartyNotices from the resolved runtime\n")
    out.append("dependencies of the shipping modules and the reviewed tables in third-party/. Do not edit.\n\n")
    out.append("1. Java libraries (").append(rows.size).append(")\n")
    out.append("2. Native code bundled inside those libraries (").append(natives.values.sumOf { it.size }).append(")\n")
    out.append("3. Rust crates linked into libtokenizers, DJL ").append(djlVersion).append(" (").append(crates.size).append(")\n")
    out.append("4. Models, data and derived source (").append(readTsv("assets.tsv").size).append(")\n")
    out.append("5. Licence and notice texts (").append(texts.size).append(")\n\n")
    out.append("\n1. JAVA LIBRARIES\n-----------------\n\n").append(maven)
    out.append("\n2. NATIVE CODE BUNDLED INSIDE THOSE LIBRARIES\n---------------------------------------------\n\n").append(nativeOut)
    out.append("\n3. RUST CRATES LINKED INTO LIBTOKENIZERS\n----------------------------------------\n\n")
    out.append("From DJL's Cargo.lock for the shipped library: crate, version, licence (Cargo.toml), origin, texts.\n\n")
    out.append(crateOut)
    out.append("\n\n4. MODELS, DATA AND DERIVED SOURCE\n----------------------------------\n\n").append(assetOut)
    out.append("\n5. LICENCE AND NOTICE TEXTS\n---------------------------\n\n").append(textOut)
    val notices = out.toString()

    val lockText = buildString {
        append("# Manifest of THIRD_PARTY_NOTICES.txt. Generated; do not edit. $regenerateHint\n")
        append("# kind\tfields... (maven: coordinate, licence, jar entries=text ids)\n")
        append("notices\tloupe-desktop/src/main/resources/THIRD_PARTY_NOTICES.txt\t")
        append(sha256(notices.toByteArray(Charsets.UTF_8))).append('\n')
        lock.forEach { append(it).append('\n') }
    }
    return notices to lockText
}

tasks.register("generateThirdPartyNotices") {
    group = "build setup"
    description = "Regenerates THIRD_PARTY_NOTICES.txt and third-party/notices.lock from the resolved runtime dependencies."
    doLast {
        val (notices, lockText) = buildThirdPartyNotices()
        noticesFile.parentFile.mkdirs()
        noticesFile.writeText(notices, Charsets.UTF_8)
        lockFile.writeText(lockText, Charsets.UTF_8)
        logger.lifecycle("Wrote ${noticesFile.relativeTo(rootDir)} (${notices.length / 1024} KB) and ${lockFile.relativeTo(rootDir)}")
    }
}

val checkThirdPartyNotices = tasks.register("checkThirdPartyNotices") {
    group = "verification"
    description = "Fails when THIRD_PARTY_NOTICES.txt or third-party/notices.lock is missing or stale."
    // Inputs, so the check reruns whenever anything it depends on changes.
    inputs.files(configurations.getByName("runtimeClasspath")).withPropertyName("runtimeClasspath")
    inputs.dir(thirdPartyDir).withPropertyName("thirdParty")
    inputs.file(noticesFile).withPropertyName("notices").optional()
    val marker = layout.buildDirectory.file("third-party-notices.ok")
    outputs.file(marker)
    doLast {
        val (notices, lockText) = buildThirdPartyNotices()
        val problems = mutableListOf<String>()
        if (!noticesFile.isFile) problems += "${noticesFile.relativeTo(rootDir)} is missing"
        else if (noticesFile.readText(Charsets.UTF_8) != notices) problems += "${noticesFile.relativeTo(rootDir)} is stale"
        if (!lockFile.isFile) problems += "${lockFile.relativeTo(rootDir)} is missing"
        else if (lockFile.readText(Charsets.UTF_8) != lockText) {
            val want = lockText.lines().filter { it.isNotEmpty() }.toSet()
            val have = lockFile.readLines(Charsets.UTF_8).toSet()
            problems += "${lockFile.relativeTo(rootDir)} is stale:\n" +
                (want - have).take(10).joinToString("") { "    + $it\n" } +
                (have - want).take(10).joinToString("") { "    - $it\n" }
        }
        if (problems.isNotEmpty()) throw GradleException(
            "Third-party notices are out of date with the resolved dependencies:\n  " +
                problems.joinToString("\n  ") + "\n$regenerateHint",
        )
        marker.get().asFile.writeText("ok\n")
    }
}

tasks.named("check") { dependsOn(checkThirdPartyNotices) }
