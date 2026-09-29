package com.omnidroid.tools.libretrodb

internal data class SliceSpec(
    val id: String,
    val systems: List<String>,
    val cores: List<String>,
)

internal object SliceLayout {
    const val SCHEMA_VERSION = 2

    val slices =
        listOf(
            SliceSpec("atari2600", listOf("atari2600"), listOf("stella")),
            SliceSpec("nes", listOf("nes"), listOf("fceumm")),
            SliceSpec("snes", listOf("snes"), listOf("snes9x")),
            SliceSpec("sms", listOf("sms"), listOf("genesis_plus_gx")),
            SliceSpec("md", listOf("md"), listOf("genesis_plus_gx")),
            SliceSpec("scd", listOf("scd"), listOf("genesis_plus_gx")),
            SliceSpec("gg", listOf("gg"), listOf("genesis_plus_gx")),
            SliceSpec("gb", listOf("gb"), listOf("gambatte")),
            SliceSpec("gbc", listOf("gbc"), listOf("gambatte")),
            SliceSpec("gba", listOf("gba"), listOf("mgba")),
            SliceSpec("n64", listOf("n64"), listOf("mupen64plus_next_gles3")),
            SliceSpec("psx", listOf("psx"), listOf("pcsx_rearmed")),
            SliceSpec("psp", listOf("psp"), listOf("ppsspp")),
            SliceSpec("arcade", listOf("fbneo", "mame2003plus"), listOf("fbneo", "mame2003_plus")),
            SliceSpec("nds", listOf("nds"), listOf("desmume", "melondsds", "melonds")),
            SliceSpec("3ds", listOf("3ds"), listOf("azahar", "citra")),
            SliceSpec("atari7800", listOf("atari7800"), listOf("prosystem")),
            SliceSpec("lynx", listOf("lynx"), listOf("handy")),
            SliceSpec("pce", listOf("pce"), listOf("mednafen_pce_fast")),
            SliceSpec("ngp", listOf("ngp"), listOf("mednafen_ngp")),
            SliceSpec("ngc", listOf("ngc"), listOf("mednafen_ngp")),
            SliceSpec("ws", listOf("ws"), listOf("mednafen_wswan")),
            SliceSpec("wsc", listOf("wsc"), listOf("mednafen_wswan")),
            SliceSpec("dos", listOf("dos"), listOf("dosbox_pure")),
            SliceSpec("ps2", listOf("ps2"), listOf("armsx2")),
            SliceSpec("gamecube", listOf("gamecube"), listOf("dolphin")),
            SliceSpec("wii", listOf("wii"), listOf("dolphin")),
            SliceSpec("dreamcast", listOf("dreamcast"), listOf("flycast")),
        )

    fun coreDirectory(coreName: String): String = "omnidroid_core_$coreName"
}
