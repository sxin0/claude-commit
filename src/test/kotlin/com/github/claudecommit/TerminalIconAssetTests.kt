package com.github.claudecommit

/** Pure checks: no application, Swing event loop, renderer, files, or network required. */
fun runTerminalIconAssetTests() {
    val ns = "http://www.w3.org/2000/svg"
    fun svg(body: String = "<path d=\"M1 2L14 8 1 14Z\" fill=\"#f80\"/>", attrs: String = ""): String =
        "<svg xmlns=\"$ns\" $attrs>$body</svg>"
    fun asset(text: String = svg(), duration: Int = 100) = TerminalIconAsset(listOf(TerminalIconFrame(text, duration)), true)
    fun normalized(text: String) = TerminalIconAssets.validateAndNormalize(asset(text)).frames.single().svg
    fun rejectAsset(value: TerminalIconAsset) {
        try {
            TerminalIconAssets.validateAndNormalize(value)
            error("Expected asset rejection")
        } catch (expected: TerminalIconException) {
            check(expected.code == "invalid_asset")
        }
    }
    fun reject(text: String) = rejectAsset(asset(text))

    val canonical = normalized(svg())
    check("width=\"16\"" in canonical && "height=\"16\"" in canonical && "viewBox=\"0 0 16.0 16.0\"" in canonical)
    check(normalized(canonical) == canonical)
    check(normalized("<s:svg xmlns:s=\"$ns\"><s:circle cx=\"8\" cy=\"8\" r=\"7\"/></s:svg>") ==
          normalized(svg("<circle cx=\"8\" cy=\"8\" r=\"7\"/>")))
    check("viewBox=\"0 0 32.0 32.0\"" in normalized(svg(attrs = "width=\"32px\" height=\"32\"")))
    check("#ff00cc" in normalized(svg("<rect x=\"1\" y=\"2\" width=\"10\" height=\"9\" fill=\"#FF00CC\"/>")))
    val complex = svg("""
        <g transform="translate(1 2) rotate(10 8 8) scale(.5)" opacity="0.7" fill="rgb(255, 12, 0)">
          <path d="M0 0h5v5l-5 0z M1 1A2 3 30 0 1 4 5" stroke="white" stroke-width="1"/>
          <ellipse cx="8" cy="8" rx="2" ry="3"/><line x1="0" x2="8" y1="0" y2="8"/>
          <polyline points="0,0 8,8"/><polygon points="0 0 8 8 8 0"/>
        </g>
    """.trimIndent(), "viewBox=\"0 0 16 16\" preserveAspectRatio=\"xMidYMid meet\"")
    check(normalized(normalized(complex)) == normalized(complex))

    // XML/parser boundaries, namespace spoofing, unsupported nodes and resource mechanisms.
    listOf(
        "<!DOCTYPE svg [<!ENTITY e SYSTEM 'file:///etc/passwd'>]>${svg("&e;")}",
        "<!DOCTYPE svg SYSTEM 'https://example.invalid/test.dtd'>${svg()}",
        "<?unsafe payload?>${svg()}", svg("<?unsafe payload?>"), svg("<!-- unsupported -->"),
        svg("<![CDATA[not a shape]]>"), svg("text"), "<svg/>", "<svg xmlns=\"urn:other\"/>",
        svg("<p:path xmlns:p=\"urn:other\" d=\"M0 0\"/>"),
        svg("<xi:include xmlns:xi=\"http://www.w3.org/2001/XInclude\" href=\"file:///etc/passwd\"/>"),
        svg("<script/>"), svg("<style/>"), svg("<image/>"), svg("<use/>"), svg("<foreignObject/>"),
        svg("<filter/>"), svg("<animate/>"), svg("<linearGradient/>"), svg("<svg/>"),
        svg("<path d=\"M0 0\"><circle r=\"1\"/></path>"),
        svg(attrs = "onload=\"alert(1)\""), svg(attrs = "href=\"https://example.invalid\""),
        svg(attrs = "xml:base=\"https://example.invalid\""),
        svg(attrs = "style=\"fill:red\""), svg(attrs = "fill=\"url(#a)\""),
        svg(attrs = "fill=\"url(data:image/svg+xml,test)\""), svg(attrs = "fill=\"var(--color)\""),
        svg(attrs = "fill=\"currentColor\""), svg(attrs = "id=\"ignored\""),
        svg(attrs = "viewBox=\"0 0 0 16\""), svg(attrs = "width=\"100%\""),
        svg(attrs = "width=\"2048\""), svg(attrs = "viewBox=\"0 0 1e9999 16\""),
        svg("<circle r=\"-1\"/>"), svg("<circle r=\"NaN\"/>"), svg("<circle r=\"Infinity\"/>"),
        svg("<rect width=\"4097\"/>"), svg("<circle opacity=\"1.1\"/>"),
        svg("<path d=\"L1 2\"/>"), svg("<path d=\"M1\"/>"), svg("<path d=\"M1 2C3 4\"/>"),
        svg("<path d=\"M0 0A1 1 0 2 0 3 3\"/>"), svg("<path d=\"M0 0A-1 1 0 0 0 3 3\"/>"),
        svg("<path d=\"M0 0R1 1\"/>"), svg("<path d=\"M0 0L1e9999 1\"/>"),
        svg("<polygon points=\"0 0 8 8\"/>"), svg("<polyline points=\"0 0 8\"/>"),
        svg(attrs = "transform=\"translate(1) bad(2)\""), svg(attrs = "transform=\"matrix(1 2)\""),
        svg(attrs = "transform=\"scale(129)\""), svg(attrs = "transform=\"skewX(90)\""),
        svg("<g transform=\"scale(16)\"><g transform=\"scale(16)\"/></g>"),
        svg(attrs = "fill=\"rgb(256,0,0)\""), "<svg xmlns=\"$ns\" fill=\"red\" fill=\"blue\"/>",
        svg("<g>".repeat(16) + "</g>".repeat(16)),
        svg("<g/>".repeat(256)), svg("<path d=\"M0 0 " + "L1 1 ".repeat(350) + "\"/>"),
        svg(" ".repeat(16 * 1024)), svg("中".repeat(6000)),
    ).forEach(::reject)

    rejectAsset(TerminalIconAsset(emptyList(), false))
    rejectAsset(TerminalIconAsset(List(65) { TerminalIconFrame(svg(), 50) }, true))
    rejectAsset(asset().copy(staticFrame = -1))
    rejectAsset(asset().copy(staticFrame = 1))
    rejectAsset(asset(duration = 49))
    rejectAsset(asset(duration = 5001))
    rejectAsset(TerminalIconAsset(List(7) { TerminalIconFrame(svg(), 5000) }, true))
    rejectAsset(TerminalIconAsset(List(64) { TerminalIconFrame(svg(" ".repeat(9000)), 50) }, true))
    check(TerminalIconAssets.validateAndNormalize(TerminalIconAsset(List(64) { TerminalIconFrame(svg(), 50) }, true)).frames.size == 64)
    check(TerminalIconAssets.validateAndNormalize(TerminalIconAsset(List(6) { TerminalIconFrame(svg(), 5000) }, false)).frames.size == 6)
    val mutableFrames = mutableListOf(TerminalIconFrame(svg(), 50))
    val snapshot = TerminalIconAssets.validateAndNormalize(TerminalIconAsset(mutableFrames, true))
    mutableFrames.clear()
    check(snapshot.frames.size == 1)

    val durations = listOf(50, 100, 150)
    fun frame(ms: Long, loop: Boolean = true, static: Boolean = false): Int = frameIndex(durations, loop, 1, static, ms)
    check(frame(-1) == 0 && frame(0) == 0 && frame(49) == 0)
    check(frame(50) == 1 && frame(149) == 1 && frame(150) == 2 && frame(299) == 2)
    check(frame(300) == 0 && frame(350) == 1 && frame(600) == 0)
    check(frame(300, loop = false) == 2 && frame(Long.MAX_VALUE, loop = false) == 2)
    check(frame(0, static = true) == 1 && frame(Long.MAX_VALUE, static = true) == 1)
    check(frameIndex(listOf(50), true, 0, false, Long.MAX_VALUE) == 0)
    check(frameIndex(durations, true, 2, true, 0) == 2)
}
