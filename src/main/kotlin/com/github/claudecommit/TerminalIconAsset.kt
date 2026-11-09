package com.github.claudecommit

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException
import java.awt.geom.AffineTransform
import java.io.ByteArrayInputStream
import java.util.Collections
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.hypot

/** Wire-independent, immutable values. SVG strings returned by validation contain only the supported subset. */
data class TerminalIconFrame(val svg: String, val durationMs: Int)
data class TerminalIconAsset(val frames: List<TerminalIconFrame>, val loop: Boolean, val staticFrame: Int = 0)

object TerminalIconAssets {
    fun validateAndNormalize(asset: TerminalIconAsset): TerminalIconAsset {
        assetRequire(asset.frames.size in 1..64, "Expected 1..64 frames")
        assetRequire(asset.staticFrame in asset.frames.indices, "static_frame is out of range")
        var totalBytes = 0
        var totalDuration = 0
        // Check inexpensive limits for the entire asset before parsing any XML.
        for (frame in asset.frames) {
            assetRequire(frame.svg.length <= 16 * 1024, "SVG exceeds the per-frame byte limit")
            val bytes = frame.svg.toByteArray(Charsets.UTF_8).size
            assetRequire(bytes in 1..16 * 1024, "SVG exceeds the per-frame byte limit")
            totalBytes += bytes
            assetRequire(totalBytes <= 512 * 1024, "SVG exceeds the total byte limit")
            assetRequire(frame.durationMs in 50..5000, "Frame duration must be 50..5000 ms")
            totalDuration += frame.durationMs
            assetRequire(totalDuration <= 30_000, "Animation exceeds 30 seconds")
        }
        val normalized = asset.frames.map { it.copy(svg = SafeTerminalSvg.normalize(it.svg)) }
        assetRequire(normalized.sumOf { it.svg.toByteArray(Charsets.UTF_8).size } <= 512 * 1024, "Normalized SVG exceeds total byte limit")
        return asset.copy(frames = Collections.unmodifiableList(normalized))
    }

    /** Called only on the service's bounded rendering worker, never the EDT. */
    fun render(asset: TerminalIconAsset, staticPlayback: Boolean): TerminalIconAnimation =
        TerminalIconAnimation.render(validateAndNormalize(asset), staticPlayback)
}

private fun assetRequire(condition: Boolean, message: String) {
    if (!condition) throw TerminalIconException("invalid_asset", message)
}

/** No original XML is ever passed to the platform SVG parser. This deliberately small language has no references. */
private object SafeTerminalSvg {
    private const val SVG_NS = "http://www.w3.org/2000/svg"
    private const val MAX_DEPTH = 16
    private const val MAX_NODES = 256
    private const val MAX_PATH_TOKENS = 1024
    private const val MAX_NUMBER = 4096.0
    private val number = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
    private val pathToken = Regex("[MmZzLlHhVvCcSsQqTtAa]|${number.pattern}")
    private val transformToken = Regex("([A-Za-z]+)\\s*\\(([^()]*)\\)")
    private val common = setOf("transform", "fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin",
                               "stroke-miterlimit", "fill-rule", "opacity", "fill-opacity", "stroke-opacity")
    private val attributes = mapOf(
        "svg" to setOf("width", "height", "viewBox", "preserveAspectRatio"),
        "g" to emptySet(),
        "path" to setOf("d"),
        "rect" to setOf("x", "y", "width", "height", "rx", "ry"),
        "circle" to setOf("cx", "cy", "r"),
        "ellipse" to setOf("cx", "cy", "rx", "ry"),
        "line" to setOf("x1", "y1", "x2", "y2"),
        "polyline" to setOf("points"),
        "polygon" to setOf("points"),
    )
    private val colors = setOf("none", "transparent", "black", "white", "gray", "grey", "red", "green", "blue",
                               "yellow", "orange", "purple", "pink", "cyan", "magenta", "lime", "navy", "teal", "silver", "maroon", "olive")

    fun normalize(svg: String): String {
        try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isValidating = false
                isXIncludeAware = false
                isExpandEntityReferences = false
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", MAX_DEPTH.toString())
            }
            val builder = factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> throw IllegalArgumentException("XML entities are not supported") }
                setErrorHandler(object : ErrorHandler {
                    override fun warning(exception: SAXParseException) = throw exception
                    override fun error(exception: SAXParseException) = throw exception
                    override fun fatalError(exception: SAXParseException) = throw exception
                })
            }
            val doc = builder.parse(ByteArrayInputStream(svg.toByteArray(Charsets.UTF_8)))
            var child = doc.firstChild
            while (child != null) {
                assetRequire(child.nodeType == Node.ELEMENT_NODE || isWhitespace(child), "Unsupported XML document node")
                child = child.nextSibling
            }
            val root = doc.documentElement
            assetRequire(root.namespaceURI == SVG_NS && root.localName == "svg", "Expected an SVG namespace root")
            val budget = Budget()
            val result = StringBuilder()
            appendElement(root, result, 1, AffineTransform(), budget)
            // Normalization can add namespace/canvas attributes. Preserve the same public byte limit.
            assetRequire(result.toString().toByteArray(Charsets.UTF_8).size <= 16 * 1024, "Normalized SVG exceeds byte limit")
            return result.toString()
        } catch (e: TerminalIconException) {
            throw e
        } catch (_: Exception) {
            // Parser errors may contain caller-controlled XML or paths: do not reflect them into the protocol/logs.
            throw TerminalIconException("invalid_asset", "Invalid SVG or secure XML parser unavailable")
        }
    }

    private class Budget(var nodes: Int = 0, var pathTokens: Int = 0)

    private fun isWhitespace(node: Node): Boolean =
        node.nodeType == Node.TEXT_NODE && node.nodeValue.all { it == ' ' || it == '\t' || it == '\r' || it == '\n' }

    private fun appendElement(element: Element, out: StringBuilder, depth: Int, parent: AffineTransform, budget: Budget) {
        assetRequire(depth <= MAX_DEPTH && ++budget.nodes <= MAX_NODES, "SVG tree exceeds complexity limit")
        val tag = element.localName
        assetRequire(element.namespaceURI == SVG_NS && tag in attributes, "Unsupported SVG element")
        assetRequire(depth == 1 || tag != "svg", "Nested SVG is not supported")
        val attrs = sortedMapOf<String, String>()
        for (index in 0 until element.attributes.length) {
            val attr = element.attributes.item(index)
            if (attr.namespaceURI == XMLConstants.XMLNS_ATTRIBUTE_NS_URI) {
                assetRequire(attr.nodeValue == SVG_NS, "Unsupported namespace declaration")
                continue
            }
            assetRequire(attr.namespaceURI.isNullOrEmpty(), "Namespaced attributes are not supported")
            val name = attr.localName ?: attr.nodeName
            assetRequire(name in common || name in attributes.getValue(tag), "Unsupported SVG attribute: $name")
            attrs[name] = normalizeAttribute(name, attr.nodeValue.trim(), tag, budget)
        }
        val transform = AffineTransform(parent)
        attrs["transform"]?.let { transform.concatenate(parseTransform(it)) }
        checkTransform(transform)
        if (depth == 1) {
            val width = attrs.remove("width")?.toDouble() ?: 16.0
            val height = attrs.remove("height")?.toDouble() ?: 16.0
            if ("viewBox" !in attrs) attrs["viewBox"] = "0 0 ${numeric(width)} ${numeric(height)}"
            attrs["width"] = "16"
            attrs["height"] = "16"
            out.append("<svg xmlns=\"").append(SVG_NS).append('"')
        } else {
            out.append('<').append(tag)
        }
        for ((name, value) in attrs) {
            // Values are generated numbers or strict ASCII enum/path/transform/color tokens, never raw XML.
            out.append(' ').append(name).append("=\"").append(value).append('"')
        }
        out.append('>')
        var child = element.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) {
                assetRequire(tag == "svg" || tag == "g", "Shape elements cannot have children")
                appendElement(child as Element, out, depth + 1, transform, budget)
            } else {
                assetRequire(isWhitespace(child), "Text, comments, entities and processing instructions are not supported")
            }
            child = child.nextSibling
        }
        out.append("</").append(tag).append('>')
    }

    private fun normalizeAttribute(name: String, value: String, tag: String, budget: Budget): String = when (name) {
        "fill", "stroke" -> color(value)
        "stroke-linecap" -> enum(value, setOf("butt", "round", "square"))
        "stroke-linejoin" -> enum(value, setOf("miter", "round", "bevel"))
        "fill-rule" -> enum(value, setOf("nonzero", "evenodd"))
        "preserveAspectRatio" -> enum(value, setOf("none", "xMidYMid", "xMidYMid meet", "xMidYMid slice"))
        "opacity", "fill-opacity", "stroke-opacity" -> bounded(value, 0.0, 1.0)
        "stroke-width" -> bounded(value, 0.0, 64.0)
        "stroke-miterlimit" -> bounded(value, 1.0, 16.0)
        "width", "height" -> if (tag == "svg") bounded(value.removeSuffix("px"), 1.0, 1024.0) else bounded(value, 0.0, MAX_NUMBER)
        "r", "rx", "ry" -> bounded(value, 0.0, MAX_NUMBER)
        "viewBox" -> {
            val numbers = numbers(value)
            assetRequire(numbers.size == 4 && numbers[2] in 1.0..1024.0 && numbers[3] in 1.0..1024.0, "Invalid viewBox")
            numbers.joinToString(" ", transform = ::numeric)
        }
        "points" -> {
            val numbers = numbers(value)
            assetRequire(numbers.size in (if (tag == "polygon") 6 else 4)..512 && numbers.size % 2 == 0, "Invalid point list")
            budget.pathTokens += numbers.size
            assetRequire(budget.pathTokens <= MAX_PATH_TOKENS, "SVG path complexity exceeds limit")
            numbers.joinToString(" ", transform = ::numeric)
        }
        "d" -> path(value, budget)
        "transform" -> {
            parseTransform(value)
            // Whitespace only is normalized; syntax and all values have already been checked.
            value.replace(Regex("\\s+"), " ")
        }
        else -> bounded(value, -MAX_NUMBER, MAX_NUMBER)
    }

    private fun enum(value: String, allowed: Set<String>): String {
        assetRequire(value in allowed, "Unsupported SVG attribute value")
        return value
    }

    private fun color(value: String): String {
        val lower = value.lowercase(java.util.Locale.ROOT)
        if (lower in colors || Regex("#[0-9a-f]{3}(?:[0-9a-f]{3})?").matches(lower)) return lower
        val rgb = Regex("rgb\\(\\s*([0-9]{1,3})\\s*,\\s*([0-9]{1,3})\\s*,\\s*([0-9]{1,3})\\s*\\)").matchEntire(lower)
        assetRequire(rgb != null && rgb.groupValues.drop(1).all { it.toInt() <= 255 }, "Only flat SVG colors are supported")
        return "rgb(${rgb!!.groupValues.drop(1).joinToString(",")})"
    }

    private fun bounded(value: String, min: Double, max: Double): String {
        assetRequire(number.matches(value), "Invalid SVG number")
        val parsed = value.toDoubleOrNull()
        assetRequire(parsed != null && parsed.isFinite() && parsed in min..max, "SVG number exceeds geometry limits")
        return numeric(parsed!!)
    }

    private fun numeric(value: Double): String = if (value == 0.0) "0" else value.toString()

    private fun numbers(value: String): List<Double> {
        val result = mutableListOf<Double>()
        var end = 0
        for (match in number.findAll(value)) {
            assetRequire(value.substring(end, match.range.first).all { it.isSvgSeparator() }, "Invalid number list")
            val parsed = match.value.toDoubleOrNull()
            assetRequire(parsed != null && parsed.isFinite() && abs(parsed) <= MAX_NUMBER, "SVG number exceeds geometry limits")
            result += parsed!!
            end = match.range.last + 1
        }
        assetRequire(value.substring(end).all { it.isSvgSeparator() }, "Invalid number list")
        return result
    }

    private fun Char.isSvgSeparator(): Boolean = this == ',' || this == ' ' || this == '\t' || this == '\r' || this == '\n'

    private fun path(value: String, budget: Budget): String {
        val tokens = mutableListOf<String>()
        var end = 0
        for (match in pathToken.findAll(value)) {
            assetRequire(value.substring(end, match.range.first).all { it.isSvgSeparator() }, "Invalid SVG path")
            tokens += match.value
            end = match.range.last + 1
        }
        assetRequire(value.substring(end).all { it.isSvgSeparator() }, "Invalid SVG path")
        budget.pathTokens += tokens.size
        assetRequire(tokens.isNotEmpty() && budget.pathTokens <= MAX_PATH_TOKENS, "SVG path complexity exceeds limit")
        assetRequire(tokens.first() == "M" || tokens.first() == "m", "Path must start with moveto")
        val arity = mapOf('M' to 2, 'L' to 2, 'H' to 1, 'V' to 1, 'C' to 6, 'S' to 4, 'Q' to 4, 'T' to 2, 'A' to 7)
        var index = 0
        val normalized = mutableListOf<String>()
        while (index < tokens.size) {
            val command = tokens[index++]
            assetRequire(command.length == 1 && command[0].isLetter(), "Expected path command")
            normalized += command
            val upper = command[0].uppercaseChar()
            if (upper == 'Z') continue
            val count = arity[upper] ?: throw TerminalIconException("invalid_asset", "Invalid SVG path command")
            val start = index
            while (index < tokens.size && !(tokens[index].length == 1 && tokens[index][0].isLetter())) index++
            assetRequire(index > start && (index - start) % count == 0, "Invalid SVG path arguments")
            for (i in start until index) {
                val n = bounded(tokens[i], -MAX_NUMBER, MAX_NUMBER)
                val parameter = (i - start) % count
                if (upper == 'A') {
                    if (parameter == 0 || parameter == 1) assetRequire(n.toDouble() >= 0, "Negative arc radius")
                    if (parameter == 3 || parameter == 4) assetRequire(tokens[i] == "0" || tokens[i] == "1", "Invalid arc flag")
                }
                normalized += if (upper == 'A' && (parameter == 3 || parameter == 4)) tokens[i] else n
            }
        }
        return normalized.joinToString(" ")
    }

    private fun parseTransform(value: String): AffineTransform {
        val result = AffineTransform()
        var end = 0
        var count = 0
        for (match in transformToken.findAll(value)) {
            assetRequire(++count <= 16 && value.substring(end, match.range.first).all { it.isSvgSeparator() }, "Invalid SVG transform")
            val n = numbers(match.groupValues[2])
            val t = when (match.groupValues[1]) {
                "matrix" -> {
                    assetRequire(n.size == 6, "Invalid matrix")
                    AffineTransform(n.toDoubleArray())
                }
                "translate" -> {
                    assetRequire(n.size in 1..2, "Invalid translate")
                    AffineTransform.getTranslateInstance(n[0], n.getOrElse(1) { 0.0 })
                }
                "scale" -> {
                    assetRequire(n.size in 1..2, "Invalid scale")
                    AffineTransform.getScaleInstance(n[0], n.getOrElse(1) { n[0] })
                }
                "rotate" -> {
                    assetRequire(n.size == 1 || n.size == 3, "Invalid rotate")
                    AffineTransform.getRotateInstance(Math.toRadians(n[0]), n.getOrElse(1) { 0.0 }, n.getOrElse(2) { 0.0 })
                }
                "skewX", "skewY" -> {
                    assetRequire(n.size == 1 && abs(n[0]) <= 80.0, "Invalid skew")
                    val tangent = kotlin.math.tan(Math.toRadians(n[0]))
                    AffineTransform.getShearInstance(if (match.groupValues[1] == "skewX") tangent else 0.0,
                                                     if (match.groupValues[1] == "skewY") tangent else 0.0)
                }
                else -> throw TerminalIconException("invalid_asset", "Unsupported SVG transform")
            }
            result.concatenate(t)
            checkTransform(result)
            end = match.range.last + 1
        }
        assetRequire(count > 0 && value.substring(end).all { it.isSvgSeparator() }, "Invalid SVG transform")
        return result
    }

    private fun checkTransform(transform: AffineTransform) {
        val m = DoubleArray(6)
        transform.getMatrix(m)
        assetRequire(m.all { it.isFinite() && abs(it) <= MAX_NUMBER } && hypot(m[0], m[1]) <= 128 && hypot(m[2], m[3]) <= 128,
                     "SVG transform exceeds geometry limits")
    }
}
