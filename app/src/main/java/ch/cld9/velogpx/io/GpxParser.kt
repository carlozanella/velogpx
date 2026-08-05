package ch.cld9.velogpx.io

import ch.cld9.velogpx.model.GpxBounds
import ch.cld9.velogpx.model.GpxCopyright
import ch.cld9.velogpx.model.GpxDocument
import ch.cld9.velogpx.model.GpxIssue
import ch.cld9.velogpx.model.GpxLink
import ch.cld9.velogpx.model.GpxMetadata
import ch.cld9.velogpx.model.GpxParseResult
import ch.cld9.velogpx.model.GpxPerson
import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.model.GpxRoute
import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.GpxTrackSegment
import ch.cld9.velogpx.model.GpxVersion
import ch.cld9.velogpx.model.IssueSeverity
import ch.cld9.velogpx.model.XmlAttribute
import ch.cld9.velogpx.model.XmlCData
import ch.cld9.velogpx.model.XmlComment
import ch.cld9.velogpx.model.XmlContent
import ch.cld9.velogpx.model.XmlElement
import ch.cld9.velogpx.model.XmlName
import ch.cld9.velogpx.model.XmlText
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.SAXNotSupportedException
import org.xml.sax.ext.DefaultHandler2
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CancellationException
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

/**
 * Streaming GPX parser. Android's DOM implementation becomes disproportionately slow and memory
 * hungry on large projects. This parser retains only the model plus the currently open XML nodes;
 * a completed point node is converted and released immediately.
 */
class GpxParser(
    private val limits: Limits = Limits(),
) {
    data class Limits(
        val maxBytes: Long = 32L * 1024L * 1024L,
        val maxPoints: Int = 2_000_000,
        val maxExtensionDepth: Int = 64,
    )

    fun parse(input: InputStream, sourceName: String? = null): GpxParseResult {
        val issues = mutableListOf<GpxIssue>()
        return try {
            checkCancelled()
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { isXIncludeAware = false }
                runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            }
            val reader = factory.newSAXParser().xmlReader
            val handler = StreamingHandler(issues, sourceName)
            reader.contentHandler = handler
            reader.errorHandler = handler
            reader.entityResolver = handler
            try {
                reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
            } catch (_: SAXNotRecognizedException) {
                // Comments and CDATA boundaries are optional preservation details on this parser.
            } catch (_: SAXNotSupportedException) {
                // Comments and CDATA boundaries are optional preservation details on this parser.
            }
            reader.parse(InputSource(DoctypeRejectingInputStream(LimitedInputStream(input, limits.maxBytes))))
            GpxParseResult(handler.document ?: error("The document has no root element"), issues)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            GpxParseResult(
                null,
                issues + GpxIssue(
                    IssueSeverity.ERROR,
                    "INVALID_GPX",
                    error.message ?: "The GPX document could not be read.",
                ),
            )
        }
    }

    private inner class StreamingHandler(
        private val issues: MutableList<GpxIssue>,
        private val sourceName: String?,
    ) : DefaultHandler2() {
        private val stack = ArrayDeque<Frame>()
        private val pendingNamespaces = linkedMapOf<String, String>()
        private val ids = ParseIds()
        private val waypoints = mutableListOf<GpxPoint>()
        private val routes = mutableListOf<GpxRoute>()
        private val tracks = mutableListOf<GpxTrack>()
        private var rootNamespace: String? = null
        private var rootFrame: Frame? = null
        private var pointCount = 0
        private var inCData = false
        var document: GpxDocument? = null
            private set

        override fun startPrefixMapping(prefix: String?, uri: String?) {
            pendingNamespaces[prefix.orEmpty()] = uri.orEmpty()
        }

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            checkCancelled()
            if (stack.size > limits.maxExtensionDepth + 8) error("Extension nesting limit exceeded")
            val qualified = qName.orEmpty()
            val local = localName?.takeIf { it.isNotEmpty() } ?: qualified.substringAfter(':')
            val parent = stack.lastOrNull()
            val namespaceUri = uri?.takeIf { it.isNotEmpty() }
            val capturePointField = parent?.let { it.isGpx("wpt") || it.isGpx("rtept") || it.isGpx("trkpt") } == true &&
                local in POINT_TEXT_CHILDREN &&
                (namespaceUri.isNullOrBlank() || namespaceUri == rootNamespace) &&
                attributes.length == 0 && pendingNamespaces.isEmpty()
            val frame = Frame(
                name = XmlName(namespaceUri, local, qualified.substringBefore(':', "").takeIf { ':' in qualified }),
                namespaceDeclarations = if (pendingNamespaces.isEmpty()) emptyMap() else LinkedHashMap(pendingNamespaces),
                attributes = if (attributes.length == 0) emptyList() else buildList(attributes.length) {
                    for (index in 0 until attributes.length) {
                        val attributeQName = attributes.getQName(index).orEmpty()
                        val attributeLocal = attributes.getLocalName(index).takeIf { it.isNotEmpty() }
                            ?: attributeQName.substringAfter(':')
                        add(
                            XmlAttribute(
                                XmlName(
                                    attributes.getURI(index).takeIf { it.isNotEmpty() },
                                    attributeLocal,
                                    attributeQName.substringBefore(':', "").takeIf { ':' in attributeQName },
                                ),
                                attributes.getValue(index),
                            ),
                        )
                    }
                },
                captureTextOnly = capturePointField,
            )
            pendingNamespaces.clear()
            if (stack.isEmpty()) {
                if (local != "gpx") error("Expected a <gpx> root element")
                rootNamespace = frame.name.namespaceUri
                rootFrame = frame
            }
            stack.addLast(frame)
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            checkCancelled()
            if (length == 0 || stack.isEmpty()) return
            val value = String(ch, start, length)
            val content: XmlContent = if (inCData) XmlCData(value) else XmlText(value)
            stack.last().append(content)
        }

        override fun startCDATA() {
            inCData = true
        }

        override fun endCDATA() {
            inCData = false
        }

        override fun comment(ch: CharArray, start: Int, length: Int) {
            if (stack.isNotEmpty()) stack.last().content += XmlComment(String(ch, start, length))
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            checkCancelled()
            val frame = stack.removeLast()
            val parent = stack.lastOrNull()
            if (frame.captureTextOnly && parent != null) {
                parent.fieldTexts[frame.name.localName] = frame.capturedText()
                return
            }
            when {
                parent == null -> finishRoot(frame)
                frame.isGpx("wpt") && parent === rootFrame -> {
                    waypoints += parsePoint(frame, "/gpx/wpt")
                    countedPoint()
                }
                frame.isGpx("rtept") && parent.isGpx("rte") -> {
                    parent.points += parsePoint(frame, "/gpx/rte/rtept[${parent.points.size}]")
                    countedPoint()
                }
                frame.isGpx("trkpt") && parent.isGpx("trkseg") -> {
                    val track = stack.elementAtOrNull(stack.size - 2)
                    val segmentIndex = track?.segments?.size ?: 0
                    parent.points += parsePoint(frame, "/gpx/trk/trkseg[$segmentIndex]/trkpt[${parent.points.size}]")
                    countedPoint()
                }
                frame.isGpx("trkseg") && parent.isGpx("trk") -> {
                    parent.segments += GpxTrackSegment(
                        points = frame.points,
                        extensions = extensionsAndForeign(frame, SEGMENT_CHILDREN),
                        id = ids.next("segment"),
                    )
                }
                frame.isGpx("rte") && parent === rootFrame -> routes += parseRoute(frame)
                frame.isGpx("trk") && parent === rootFrame -> tracks += parseTrack(frame)
                else -> parent.content += frame.toXml()
            }
        }

        private fun countedPoint() {
            pointCount++
            if (pointCount > limits.maxPoints) error("Point limit of ${limits.maxPoints} exceeded")
        }

        private fun finishRoot(root: Frame) {
            if (pointCount > limits.maxPoints) error("Point limit of ${limits.maxPoints} exceeded")
            val version = GpxVersion.from(root.attr("version"), root.name.namespaceUri)
            val namespace = root.name.namespaceUri
            if (namespace.isNullOrBlank()) {
                issues += GpxIssue(
                    IssueSeverity.WARNING,
                    "MISSING_NAMESPACE",
                    "The file has no GPX namespace; it was imported in compatibility mode.",
                    "/gpx",
                )
            } else if (namespace != version.namespace) {
                issues += GpxIssue(
                    IssueSeverity.WARNING,
                    "VERSION_NAMESPACE_MISMATCH",
                    "GPX version and namespace do not agree; version ${version.value} was inferred.",
                    "/gpx",
                )
            }
            val metadata = if (version == GpxVersion.V1_0) {
                parseMetadata10(root)
            } else {
                root.child("metadata")?.let(::parseMetadata11)
            }
            document = GpxDocument(
                version = version,
                creator = root.attr("creator") ?: "Unknown",
                metadata = metadata,
                waypoints = waypoints,
                routes = routes,
                tracks = tracks,
                rootExtensions = extensionsAndForeign(root, ROOT_CHILDREN),
                namespaceDeclarations = root.namespaceDeclarations,
                sourceName = sourceName,
            )
        }

        private fun Frame.isGpx(localName: String): Boolean =
            name.localName == localName && (name.namespaceUri.isNullOrBlank() || name.namespaceUri == rootNamespace)

        private fun Frame.gpxChildren(name: String): List<XmlElement> = elements.filter { it.isGpx(name) }
        private fun Frame.child(name: String): XmlElement? = elements.firstOrNull { it.isGpx(name) }
        private fun Frame.textOf(name: String): String? = fieldText(name) ?: child(name)?.textContent()
        private fun Frame.doubleOf(name: String, issues: MutableList<GpxIssue>, path: String): Double? {
            val text = textOf(name) ?: return null
            val value = text.toDoubleOrNull()
            if (value == null || !value.isFinite()) {
                issues += GpxIssue(
                    IssueSeverity.WARNING,
                    "INVALID_NUMBER",
                    "Invalid $name '$text' was ignored.",
                    "$path/$name",
                )
                return null
            }
            return value
        }

        private fun XmlElement.isGpx(localName: String): Boolean =
            name.localName == localName && (name.namespaceUri.isNullOrBlank() || name.namespaceUri == rootNamespace)

        private fun XmlElement.child(name: String): XmlElement? =
            children.asSequence().filterIsInstance<XmlElement>().firstOrNull { it.isGpx(name) }

        private fun XmlElement.children(name: String): List<XmlElement> =
            children.filterIsInstance<XmlElement>().filter { it.isGpx(name) }

        private fun XmlElement.textOf(name: String): String? = child(name)?.textContent()

        private fun parseMetadata11(element: XmlElement) = GpxMetadata(
            name = element.textOf("name"),
            description = element.textOf("desc"),
            author = element.child("author")?.let(::parsePerson),
            copyright = element.child("copyright")?.let(::parseCopyright),
            links = element.children("link").map(::parseLink),
            timeText = element.textOf("time"),
            time = parseTime(element.textOf("time"), issues, "/gpx/metadata/time"),
            keywords = element.textOf("keywords"),
            bounds = element.child("bounds")?.let { parseBounds(it, issues, "/gpx/metadata/bounds") },
            extensions = element.child("extensions")?.children?.filterIsInstance<XmlElement>().orEmpty(),
        )

        private fun parseMetadata10(root: Frame): GpxMetadata? {
            val name = root.textOf("name")
            val description = root.textOf("desc")
            val authorName = root.textOf("author")
            val email = root.textOf("email")
            val url = root.textOf("url")
            val urlName = root.textOf("urlname")
            val timeText = root.textOf("time")
            val keywords = root.textOf("keywords")
            val bounds = root.child("bounds")?.let { parseBounds(it, issues, "/gpx/bounds") }
            if (listOf(name, description, authorName, email, url, timeText, keywords).all { it == null } && bounds == null) return null
            val parts = email?.split('@', limit = 2)
            return GpxMetadata(
                name = name,
                description = description,
                author = if (authorName != null || email != null) {
                    GpxPerson(
                        name = authorName,
                        emailId = parts?.getOrNull(0),
                        emailDomain = parts?.getOrNull(1),
                        link = url?.let { GpxLink(it, urlName) },
                    )
                } else null,
                links = url?.let { listOf(GpxLink(it, urlName)) }.orEmpty(),
                timeText = timeText,
                time = parseTime(timeText, issues, "/gpx/time"),
                keywords = keywords,
                bounds = bounds,
            )
        }

        private fun parsePerson(element: XmlElement): GpxPerson {
            val email = element.child("email")
            return GpxPerson(
                name = element.textOf("name"),
                emailId = email?.attr("id"),
                emailDomain = email?.attr("domain"),
                link = element.child("link")?.let(::parseLink),
            )
        }

        private fun parseCopyright(element: XmlElement) = GpxCopyright(
            author = element.attr("author").orEmpty(),
            year = element.textOf("year")?.toIntOrNull(),
            license = element.textOf("license"),
        )

        private fun parseLink(element: XmlElement) = GpxLink(
            href = element.attr("href") ?: element.textContent().trim(),
            text = element.textOf("text"),
            type = element.textOf("type"),
        )

        private fun parseRoute(frame: Frame) = GpxRoute(
            name = frame.textOf("name"),
            comment = frame.textOf("cmt"),
            description = frame.textOf("desc"),
            source = frame.textOf("src"),
            links = linksFrom(frame),
            number = frame.textOf("number")?.toIntOrNull(),
            type = frame.textOf("type"),
            points = frame.points,
            extensions = extensionsAndForeign(frame, ROUTE_CHILDREN),
            id = ids.next("route"),
        )

        private fun parseTrack(frame: Frame) = GpxTrack(
            name = frame.textOf("name"),
            comment = frame.textOf("cmt"),
            description = frame.textOf("desc"),
            source = frame.textOf("src"),
            links = linksFrom(frame),
            number = frame.textOf("number")?.toIntOrNull(),
            type = frame.textOf("type"),
            segments = frame.segments,
            extensions = extensionsAndForeign(frame, TRACK_CHILDREN),
            id = ids.next("track"),
        )

        private fun parsePoint(frame: Frame, path: String): GpxPoint {
            val latitude = parseCoordinate(frame.attr("lat"), -90.0, 90.0, "latitude", issues, path)
            val longitude = parseCoordinate(frame.attr("lon"), -180.0, 180.0, "longitude", issues, path)
            val timeText = frame.textOf("time")
            return GpxPoint(
                latitude = latitude,
                longitude = longitude,
                elevation = frame.doubleOf("ele", issues, path),
                timeText = timeText,
                time = parseTime(timeText, issues, "$path/time"),
                magneticVariation = frame.doubleOf("magvar", issues, path),
                geoidHeight = frame.doubleOf("geoidheight", issues, path),
                name = frame.textOf("name"),
                comment = frame.textOf("cmt"),
                description = frame.textOf("desc"),
                source = frame.textOf("src"),
                links = linksFrom(frame),
                symbol = frame.textOf("sym"),
                type = frame.textOf("type"),
                fix = frame.textOf("fix"),
                satellites = frame.textOf("sat")?.toIntOrNull(),
                hdop = frame.doubleOf("hdop", issues, path),
                vdop = frame.doubleOf("vdop", issues, path),
                pdop = frame.doubleOf("pdop", issues, path),
                ageOfDgpsData = frame.doubleOf("ageofdgpsdata", issues, path),
                dgpsId = frame.textOf("dgpsid")?.toIntOrNull(),
                course = frame.doubleOf("course", issues, path),
                speed = frame.doubleOf("speed", issues, path),
                extensions = extensionsAndForeign(frame, POINT_CHILDREN),
                id = ids.next("point"),
            )
        }

        private fun linksFrom(frame: Frame): List<GpxLink> {
            val modern = frame.gpxChildren("link").map(::parseLink)
            if (modern.isNotEmpty()) return modern
            return frame.textOf("url")?.let { listOf(GpxLink(it, frame.textOf("urlname"))) }.orEmpty()
        }

        private fun extensionsAndForeign(frame: Frame, standardNames: Set<String>): List<XmlElement> = buildList {
            for (child in frame.elements) {
                when {
                    child.isGpx("extensions") -> addAll(child.children.filterIsInstance<XmlElement>())
                    child.name.namespaceUri != rootNamespace && !child.name.namespaceUri.isNullOrBlank() -> add(child)
                    child.name.localName !in standardNames -> add(child)
                }
            }
        }
    }

    private class Frame(
        val name: XmlName,
        val namespaceDeclarations: Map<String, String>,
        val attributes: List<XmlAttribute>,
        val captureTextOnly: Boolean,
    ) {
        private var mutableContent: MutableList<XmlContent>? = null
        val content: MutableList<XmlContent> get() = mutableContent ?: mutableListOf<XmlContent>().also { mutableContent = it }
        private var textBuilder: StringBuilder? = null
        private var mutableFieldTexts: MutableMap<String, String>? = null
        val fieldTexts: MutableMap<String, String>
            get() = mutableFieldTexts ?: mutableMapOf<String, String>().also { mutableFieldTexts = it }
        fun fieldText(name: String): String? = mutableFieldTexts?.get(name)
        fun capturedText(): String = textBuilder?.toString().orEmpty()
        private var mutablePoints: MutableList<GpxPoint>? = null
        val points: MutableList<GpxPoint>
            get() = mutablePoints ?: mutableListOf<GpxPoint>().also { mutablePoints = it }
        private var mutableSegments: MutableList<GpxTrackSegment>? = null
        val segments: MutableList<GpxTrackSegment>
            get() = mutableSegments ?: mutableListOf<GpxTrackSegment>().also { mutableSegments = it }
        private var cachedElements: List<XmlElement>? = null
        // Frames are queried only after their closing tag. Cache this view because a point has many
        // optional GPX fields and repeatedly filtering its content dominated Android parse time.
        val elements: List<XmlElement>
            get() = cachedElements ?: mutableContent.orEmpty().filterIsInstance<XmlElement>().also { cachedElements = it }

        fun attr(name: String): String? = attributes.firstOrNull { it.name.namespaceUri.isNullOrBlank() && it.name.localName == name }?.value
        fun toXml() = XmlElement(name, namespaceDeclarations, attributes, mutableContent.orEmpty())

        fun append(value: XmlContent) {
            if (captureTextOnly) {
                when (value) {
                    is XmlText -> (textBuilder ?: StringBuilder().also { textBuilder = it }).append(value.value)
                    is XmlCData -> (textBuilder ?: StringBuilder().also { textBuilder = it }).append(value.value)
                    else -> Unit
                }
                return
            }
            val content = content
            val previous = content.lastOrNull()
            when {
                previous is XmlText && value is XmlText -> content[content.lastIndex] = XmlText(previous.value + value.value)
                previous is XmlCData && value is XmlCData -> content[content.lastIndex] = XmlCData(previous.value + value.value)
                else -> content += value
            }
        }
    }

    private fun parseBounds(element: XmlElement, issues: MutableList<GpxIssue>, path: String): GpxBounds? = try {
        GpxBounds(
            minLatitude = element.attr("minlat")!!.toDouble(),
            minLongitude = element.attr("minlon")!!.toDouble(),
            maxLatitude = element.attr("maxlat")!!.toDouble(),
            maxLongitude = element.attr("maxlon")!!.toDouble(),
        )
    } catch (_: Exception) {
        issues += GpxIssue(IssueSeverity.WARNING, "INVALID_BOUNDS", "Invalid bounds were ignored.", path)
        null
    }

    private fun parseCoordinate(
        text: String?,
        minimum: Double,
        maximum: Double,
        label: String,
        issues: MutableList<GpxIssue>,
        path: String,
    ): Double {
        val value = text?.toDoubleOrNull()
        if (value == null || !value.isFinite() || value !in minimum..maximum) {
            issues += GpxIssue(IssueSeverity.ERROR, "INVALID_COORDINATE", "Invalid $label '$text'.", path)
            error("Invalid $label at $path")
        }
        return value
    }

    private fun parseTime(text: String?, issues: MutableList<GpxIssue>, path: String): Instant? {
        if (text == null) return null
        return runCatching { Instant.parse(text) }
            .recoverCatching { OffsetDateTime.parse(text).toInstant() }
            .getOrElse {
                issues += GpxIssue(IssueSeverity.WARNING, "INVALID_TIME", "Invalid timestamp '$text' was retained as text.", path)
                null
            }
    }

    private class LimitedInputStream(input: InputStream, private val maximum: Long) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int {
            checkCancelled()
            return super.read().also { if (it >= 0) checked(1) }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkCancelled()
            return super.read(buffer, offset, length).also { if (it > 0) checked(it.toLong()) }
        }
        private fun checked(read: Long) {
            count += read
            if (count > maximum) error("GPX size limit of $maximum bytes exceeded")
        }
    }

    private class DoctypeRejectingInputStream(input: InputStream) : FilterInputStream(input) {
        private val forbidden = "<!DOCTYPE".encodeToByteArray()
        private var matched = 0

        override fun read(): Int = super.read().also { if (it >= 0) inspect(it.toByte()) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { count ->
                if (count > 0) for (index in offset until offset + count) inspect(buffer[index])
            }

        private fun inspect(byte: Byte) {
            val upper = byte.toInt().toChar().uppercaseChar().code.toByte()
            if (upper == forbidden[matched]) {
                matched++
                if (matched == forbidden.size) throw IOException("DOCTYPE declarations are not allowed in GPX files")
            } else {
                matched = if (upper == forbidden[0]) 1 else 0
            }
        }
    }

    companion object {
        private val ROOT_CHILDREN = setOf(
            "metadata", "wpt", "rte", "trk", "extensions",
            "name", "desc", "author", "email", "url", "urlname", "time", "keywords", "bounds",
        )
        private val ROUTE_CHILDREN = setOf("name", "cmt", "desc", "src", "link", "url", "urlname", "number", "type", "extensions", "rtept")
        private val TRACK_CHILDREN = setOf("name", "cmt", "desc", "src", "link", "url", "urlname", "number", "type", "extensions", "trkseg")
        private val SEGMENT_CHILDREN = setOf("trkpt", "extensions")
        private val POINT_CHILDREN = setOf(
            "ele", "time", "magvar", "geoidheight", "name", "cmt", "desc", "src", "link", "url", "urlname",
            "sym", "type", "fix", "sat", "hdop", "vdop", "pdop", "ageofdgpsdata", "dgpsid", "course", "speed", "extensions",
        )
        private val POINT_TEXT_CHILDREN = POINT_CHILDREN - setOf("link", "extensions")
    }

    private class ParseIds {
        private val prefix = UUID.randomUUID().toString()
        private var next = 0L
        fun next(kind: String): String = "$prefix-$kind-${next++}"
    }
}

private fun XmlElement.attr(name: String): String? =
    attributes.firstOrNull { it.name.namespaceUri.isNullOrBlank() && it.name.localName == name }?.value

private fun XmlElement.textContent(): String = buildString {
    fun appendContent(content: XmlContent) {
        when (content) {
            is XmlText -> append(content.value)
            is XmlCData -> append(content.value)
            is XmlElement -> content.children.forEach(::appendContent)
            is XmlComment -> Unit
        }
    }
    children.forEach(::appendContent)
}

private fun checkCancelled() {
    if (Thread.currentThread().isInterrupted) throw CancellationException("GPX parsing cancelled")
}
