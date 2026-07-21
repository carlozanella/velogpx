package ch.cld9.velogpx.io

import ch.cld9.velogpx.model.GpxDocument
import ch.cld9.velogpx.model.GpxLink
import ch.cld9.velogpx.model.GpxMetadata
import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.model.GpxRoute
import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.GpxVersion
import ch.cld9.velogpx.model.XmlCData
import ch.cld9.velogpx.model.XmlComment
import ch.cld9.velogpx.model.XmlElement
import ch.cld9.velogpx.model.XmlText
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.math.BigDecimal

class GpxWriter {
    fun write(document: GpxDocument, output: OutputStream, version: GpxVersion = document.version) {
        val writer = BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8))
        writer.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        writer.append("<gpx xmlns=\"").append(version.namespace).append("\"")
        writer.append(" version=\"").append(version.value).append("\"")
        writer.append(" creator=\"").append(escapeAttribute(document.creator)).append("\"")
        writer.append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
        writer.append(" xsi:schemaLocation=\"").append(version.namespace).append(' ')
            .append(version.namespace).append("/gpx.xsd\"")
        val legacyTelemetry = version == GpxVersion.V1_1 && hasLegacyTelemetry(document)
        document.namespaceDeclarations.forEach { (prefix, uri) ->
            if (prefix.isNotBlank() && uri != version.namespace && prefix != "xsi" && !(prefix == "velogpx" && legacyTelemetry)) {
                writer.append(" xmlns")
                if (prefix.isNotBlank()) writer.append(':').append(prefix)
                writer.append("=\"").append(escapeAttribute(uri)).append('"')
            }
        }
        if (legacyTelemetry) {
            writer.append(" xmlns:velogpx=\"https://cld9.ch/velogpx/extensions/1\"")
        }
        writer.append(">\n")

        document.metadata?.let { sourceMetadata ->
            val points = document.waypoints + document.routes.flatMap { it.points } +
                document.tracks.flatMap { track -> track.segments.flatMap { it.points } }
            val metadata = if (points.isEmpty()) sourceMetadata else sourceMetadata.copy(
                bounds = ch.cld9.velogpx.model.GpxBounds(
                    minLatitude = points.minOf { it.latitude },
                    minLongitude = points.minOf { it.longitude },
                    maxLatitude = points.maxOf { it.latitude },
                    maxLongitude = points.maxOf { it.longitude },
                ),
            )
            if (version == GpxVersion.V1_1) writeMetadata11(writer, metadata, 1)
            else writeMetadata10(writer, metadata, 1)
        }
        document.waypoints.forEach { writePoint(writer, "wpt", it, version, 1) }
        document.routes.forEach { writeRoute(writer, it, version, 1) }
        document.tracks.forEach { writeTrack(writer, it, version, 1) }
        writeExtensions(writer, document.rootExtensions, version, 1)
        writer.append("</gpx>\n")
        writer.flush()
    }

    private fun writeMetadata11(writer: Appendable, value: GpxMetadata, depth: Int) {
        line(writer, depth, "<metadata>")
        element(writer, depth + 1, "name", value.name)
        element(writer, depth + 1, "desc", value.description)
        value.author?.let { author ->
            line(writer, depth + 1, "<author>")
            element(writer, depth + 2, "name", author.name)
            if (author.emailId != null && author.emailDomain != null) {
                line(
                    writer,
                    depth + 2,
                    "<email id=\"${escapeAttribute(author.emailId)}\" domain=\"${escapeAttribute(author.emailDomain)}\"/>",
                )
            }
            author.link?.let { writeLink(writer, it, depth + 2) }
            line(writer, depth + 1, "</author>")
        }
        value.copyright?.let { copyright ->
            line(writer, depth + 1, "<copyright author=\"${escapeAttribute(copyright.author)}\">")
            element(writer, depth + 2, "year", copyright.year?.toString())
            element(writer, depth + 2, "license", copyright.license)
            line(writer, depth + 1, "</copyright>")
        }
        value.links.forEach { writeLink(writer, it, depth + 1) }
        element(writer, depth + 1, "time", timeText(value.time, value.timeText))
        element(writer, depth + 1, "keywords", value.keywords)
        value.bounds?.let {
            line(
                writer,
                depth + 1,
                "<bounds minlat=\"${number(it.minLatitude)}\" minlon=\"${number(it.minLongitude)}\" " +
                    "maxlat=\"${number(it.maxLatitude)}\" maxlon=\"${number(it.maxLongitude)}\"/>",
            )
        }
        writeExtensions(writer, value.extensions, GpxVersion.V1_1, depth + 1)
        line(writer, depth, "</metadata>")
    }

    private fun writeMetadata10(writer: Appendable, value: GpxMetadata, depth: Int) {
        element(writer, depth, "name", value.name)
        element(writer, depth, "desc", value.description)
        element(writer, depth, "author", value.author?.name)
        val email = value.author?.let { author ->
            if (author.emailId != null && author.emailDomain != null) "${author.emailId}@${author.emailDomain}" else null
        }
        element(writer, depth, "email", email)
        val link = value.author?.link ?: value.links.firstOrNull()
        element(writer, depth, "url", link?.href)
        element(writer, depth, "urlname", link?.text)
        element(writer, depth, "time", timeText(value.time, value.timeText))
        element(writer, depth, "keywords", value.keywords)
        value.bounds?.let {
            line(
                writer,
                depth,
                "<bounds minlat=\"${number(it.minLatitude)}\" minlon=\"${number(it.minLongitude)}\" " +
                    "maxlat=\"${number(it.maxLatitude)}\" maxlon=\"${number(it.maxLongitude)}\"/>",
            )
        }
        value.extensions.forEach { writeXmlElement(writer, it, depth) }
    }

    private fun writeRoute(writer: Appendable, route: GpxRoute, version: GpxVersion, depth: Int) {
        line(writer, depth, "<rte>")
        writeCommonPathFields(
            writer, version, depth + 1, route.name, route.comment, route.description, route.source,
            route.links, route.number, route.type,
        )
        writeExtensions(writer, route.extensions, version, depth + 1)
        route.points.forEach { writePoint(writer, "rtept", it, version, depth + 1) }
        line(writer, depth, "</rte>")
    }

    private fun writeTrack(writer: Appendable, track: GpxTrack, version: GpxVersion, depth: Int) {
        line(writer, depth, "<trk>")
        writeCommonPathFields(
            writer, version, depth + 1, track.name, track.comment, track.description, track.source,
            track.links, track.number, track.type,
        )
        writeExtensions(writer, track.extensions, version, depth + 1)
        track.segments.forEach { segment ->
            line(writer, depth + 1, "<trkseg>")
            segment.points.forEach { writePoint(writer, "trkpt", it, version, depth + 2) }
            writeExtensions(writer, segment.extensions, version, depth + 2)
            line(writer, depth + 1, "</trkseg>")
        }
        line(writer, depth, "</trk>")
    }

    private fun writeCommonPathFields(
        writer: Appendable,
        version: GpxVersion,
        depth: Int,
        name: String?,
        comment: String?,
        description: String?,
        source: String?,
        links: List<GpxLink>,
        routeNumber: Int?,
        type: String?,
    ) {
        element(writer, depth, "name", name)
        element(writer, depth, "cmt", comment)
        element(writer, depth, "desc", description)
        element(writer, depth, "src", source)
        if (version == GpxVersion.V1_1) links.forEach { writeLink(writer, it, depth) }
        else links.firstOrNull()?.let {
            element(writer, depth, "url", it.href)
            element(writer, depth, "urlname", it.text)
        }
        element(writer, depth, "number", routeNumber?.toString())
        if (version == GpxVersion.V1_1) element(writer, depth, "type", type)
    }

    private fun writePoint(writer: Appendable, tag: String, point: GpxPoint, version: GpxVersion, depth: Int) {
        line(writer, depth, "<$tag lat=\"${number(point.latitude)}\" lon=\"${number(point.longitude)}\">")
        element(writer, depth + 1, "ele", point.elevation?.let(::number))
        element(writer, depth + 1, "time", timeText(point.time, point.timeText))
        if (version == GpxVersion.V1_0) {
            element(writer, depth + 1, "course", point.course?.let(::number))
            element(writer, depth + 1, "speed", point.speed?.let(::number))
        }
        element(writer, depth + 1, "magvar", point.magneticVariation?.let(::number))
        element(writer, depth + 1, "geoidheight", point.geoidHeight?.let(::number))
        element(writer, depth + 1, "name", point.name)
        element(writer, depth + 1, "cmt", point.comment)
        element(writer, depth + 1, "desc", point.description)
        element(writer, depth + 1, "src", point.source)
        if (version == GpxVersion.V1_1) point.links.forEach { writeLink(writer, it, depth + 1) }
        else point.links.firstOrNull()?.let {
            element(writer, depth + 1, "url", it.href)
            element(writer, depth + 1, "urlname", it.text)
        }
        element(writer, depth + 1, "sym", point.symbol)
        element(writer, depth + 1, "type", point.type)
        element(writer, depth + 1, "fix", point.fix)
        element(writer, depth + 1, "sat", point.satellites?.toString())
        element(writer, depth + 1, "hdop", point.hdop?.let(::number))
        element(writer, depth + 1, "vdop", point.vdop?.let(::number))
        element(writer, depth + 1, "pdop", point.pdop?.let(::number))
        element(writer, depth + 1, "ageofdgpsdata", point.ageOfDgpsData?.let(::number))
        element(writer, depth + 1, "dgpsid", point.dgpsId?.toString())
        val extensions = buildList {
            addAll(point.extensions)
            if (version == GpxVersion.V1_1) {
                point.course?.let { add(simpleExtension("course", number(it))) }
                point.speed?.let { add(simpleExtension("speed", number(it))) }
            }
        }
        writeExtensions(writer, extensions, version, depth + 1)
        line(writer, depth, "</$tag>")
    }

    private fun writeLink(writer: Appendable, link: GpxLink, depth: Int) {
        line(writer, depth, "<link href=\"${escapeAttribute(link.href)}\">")
        element(writer, depth + 1, "text", link.text)
        element(writer, depth + 1, "type", link.type)
        line(writer, depth, "</link>")
    }

    private fun writeExtensions(writer: Appendable, elements: List<XmlElement>, version: GpxVersion, depth: Int) {
        if (elements.isEmpty()) return
        if (version == GpxVersion.V1_1) line(writer, depth, "<extensions>")
        elements.forEach { writeXmlElement(writer, it, if (version == GpxVersion.V1_1) depth + 1 else depth) }
        if (version == GpxVersion.V1_1) line(writer, depth, "</extensions>")
    }

    private fun writeXmlElement(writer: Appendable, element: XmlElement, depth: Int, inline: Boolean = false) {
        if (!inline) indent(writer, depth)
        writer.append('<').append(element.name.qualifiedName)
        element.namespaceDeclarations.forEach { (prefix, uri) ->
            writer.append(" xmlns")
            if (prefix.isNotBlank()) writer.append(':').append(prefix)
            writer.append("=\"").append(escapeAttribute(uri)).append('"')
        }
        if (!element.name.namespaceUri.isNullOrBlank()) {
            if (element.name.prefix == null && element.namespaceDeclarations[""] != element.name.namespaceUri) {
                writer.append(" xmlns=\"").append(escapeAttribute(element.name.namespaceUri)).append('"')
            } else if (element.name.prefix != null && element.name.prefix !in element.namespaceDeclarations) {
                writer.append(" xmlns:").append(element.name.prefix).append("=\"")
                    .append(escapeAttribute(element.name.namespaceUri)).append('"')
            }
        }
        val declaredPrefixes = element.namespaceDeclarations.keys.toMutableSet().apply { element.name.prefix?.let(::add) }
        element.attributes.forEach { attribute ->
            if (!attribute.name.prefix.isNullOrBlank() && attribute.name.prefix !in declaredPrefixes &&
                !attribute.name.namespaceUri.isNullOrBlank()
            ) {
                writer.append(" xmlns:").append(attribute.name.prefix).append("=\"")
                    .append(escapeAttribute(attribute.name.namespaceUri)).append('"')
                declaredPrefixes += attribute.name.prefix
            }
            writer.append(' ').append(attribute.name.qualifiedName).append("=\"")
                .append(escapeAttribute(attribute.value)).append('"')
        }
        if (element.children.isEmpty()) {
            writer.append("/>")
            if (!inline) writer.append('\n')
            return
        }
        writer.append('>')
        val hasStructuredChild = element.children.any { it is XmlElement || it is XmlComment }
        val hasTextContent = element.children.any { it is XmlText || it is XmlCData }
        val pretty = !inline && hasStructuredChild && !hasTextContent
        if (pretty) writer.append('\n')
        element.children.forEach { child ->
            when (child) {
                is XmlElement -> writeXmlElement(writer, child, depth + 1, inline = !pretty)
                is XmlText -> writer.append(escapeText(child.value))
                is XmlCData -> writer.append("<![CDATA[").append(child.value.replace("]]>", "]]]]><![CDATA[>"))
                    .append("]]>")
                is XmlComment -> {
                    if (pretty) indent(writer, depth + 1)
                    writer.append("<!--").append(child.value.replace("--", "- -")).append("-->")
                    if (pretty) writer.append('\n')
                }
            }
        }
        if (pretty) indent(writer, depth)
        writer.append("</").append(element.name.qualifiedName).append('>')
        if (!inline) writer.append('\n')
    }

    private fun simpleExtension(name: String, value: String) = XmlElement(
        name = ch.cld9.velogpx.model.XmlName("https://cld9.ch/velogpx/extensions/1", name, "velogpx"),
        children = listOf(XmlText(value)),
    )

    private fun hasLegacyTelemetry(document: GpxDocument): Boolean =
        document.waypoints.any { it.course != null || it.speed != null } ||
            document.routes.any { route -> route.points.any { it.course != null || it.speed != null } } ||
            document.tracks.any { track -> track.segments.any { segment -> segment.points.any { it.course != null || it.speed != null } } }

    private fun element(writer: Appendable, depth: Int, tag: String, value: String?) {
        if (value == null) return
        if (value.isEmpty()) line(writer, depth, "<$tag/>")
        else line(writer, depth, "<$tag>${escapeText(value)}</$tag>")
    }

    private fun line(writer: Appendable, depth: Int, value: String) {
        indent(writer, depth)
        writer.append(value).append('\n')
    }

    private fun indent(writer: Appendable, depth: Int) {
        repeat(depth) { writer.append("  ") }
    }

    private fun timeText(time: Instant?, original: String?): String? = when {
        time == null -> original
        original != null && runCatching { Instant.parse(original) }.getOrNull() == time -> original
        else -> time.toString()
    }

    private fun number(value: Double): String = when {
        value == 0.0 -> "0"
        !value.isFinite() -> error("GPX cannot represent a non-finite number")
        else -> BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }

    private fun escapeText(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun escapeAttribute(value: String): String = escapeText(value)
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
