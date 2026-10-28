package app.astroexposure.domain.catalog

/**
 * Localized names for the 88 IAU constellations (ui-design §7.5).
 *
 * The object catalogue stores the three-letter IAU code
 * ([DeepSkyObject.constellation], e.g. "And"); showing the raw code in
 * the UI is a display defect — the interface language's name belongs
 * in its place. This table is the single code → name mapping: the
 * Chinese names are the standard Chinese constellation names, the
 * English ones the IAU Latin names. An unknown code resolves to null
 * here and to the code itself in [displayName] — never to an invented
 * name and never to a blank.
 */
object ConstellationNames {

    private data class Names(val zh: String, val en: String)

    private val NAMES: Map<String, Names> = mapOf(
        "And" to Names("仙女座", "Andromeda"),
        "Ant" to Names("唧筒座", "Antlia"),
        "Aps" to Names("天燕座", "Apus"),
        "Aql" to Names("天鹰座", "Aquila"),
        "Aqr" to Names("宝瓶座", "Aquarius"),
        "Ara" to Names("天坛座", "Ara"),
        "Ari" to Names("白羊座", "Aries"),
        "Aur" to Names("御夫座", "Auriga"),
        "Boo" to Names("牧夫座", "Boötes"),
        "Cae" to Names("雕具座", "Caelum"),
        "Cam" to Names("鹿豹座", "Camelopardalis"),
        "Cap" to Names("摩羯座", "Capricornus"),
        "Car" to Names("船底座", "Carina"),
        "Cas" to Names("仙后座", "Cassiopeia"),
        "Cen" to Names("半人马座", "Centaurus"),
        "Cep" to Names("仙王座", "Cepheus"),
        "Cet" to Names("鲸鱼座", "Cetus"),
        "Cha" to Names("蝘蜓座", "Chamaeleon"),
        "Cir" to Names("圆规座", "Circinus"),
        "CMa" to Names("大犬座", "Canis Major"),
        "CMi" to Names("小犬座", "Canis Minor"),
        "Cnc" to Names("巨蟹座", "Cancer"),
        "Col" to Names("天鸽座", "Columba"),
        "Com" to Names("后发座", "Coma Berenices"),
        "CrA" to Names("南冕座", "Corona Australis"),
        "CrB" to Names("北冕座", "Corona Borealis"),
        "Crt" to Names("巨爵座", "Crater"),
        "Cru" to Names("南十字座", "Crux"),
        "Crv" to Names("乌鸦座", "Corvus"),
        "CVn" to Names("猎犬座", "Canes Venatici"),
        "Cyg" to Names("天鹅座", "Cygnus"),
        "Del" to Names("海豚座", "Delphinus"),
        "Dor" to Names("剑鱼座", "Dorado"),
        "Dra" to Names("天龙座", "Draco"),
        "Equ" to Names("小马座", "Equuleus"),
        "Eri" to Names("波江座", "Eridanus"),
        "For" to Names("天炉座", "Fornax"),
        "Gem" to Names("双子座", "Gemini"),
        "Gru" to Names("天鹤座", "Grus"),
        "Her" to Names("武仙座", "Hercules"),
        "Hor" to Names("时钟座", "Horologium"),
        "Hya" to Names("长蛇座", "Hydra"),
        "Hyi" to Names("水蛇座", "Hydrus"),
        "Ind" to Names("印第安座", "Indus"),
        "Lac" to Names("蝎虎座", "Lacerta"),
        "Leo" to Names("狮子座", "Leo"),
        "Lep" to Names("天兔座", "Lepus"),
        "Lib" to Names("天秤座", "Libra"),
        "LMi" to Names("小狮座", "Leo Minor"),
        "Lup" to Names("豺狼座", "Lupus"),
        "Lyn" to Names("天猫座", "Lynx"),
        "Lyr" to Names("天琴座", "Lyra"),
        "Men" to Names("山案座", "Mensa"),
        "Mic" to Names("显微镜座", "Microscopium"),
        "Mon" to Names("麒麟座", "Monoceros"),
        "Mus" to Names("苍蝇座", "Musca"),
        "Nor" to Names("矩尺座", "Norma"),
        "Oct" to Names("南极座", "Octans"),
        "Oph" to Names("蛇夫座", "Ophiuchus"),
        "Ori" to Names("猎户座", "Orion"),
        "Pav" to Names("孔雀座", "Pavo"),
        "Peg" to Names("飞马座", "Pegasus"),
        "Per" to Names("英仙座", "Perseus"),
        "Phe" to Names("凤凰座", "Phoenix"),
        "Pic" to Names("绘架座", "Pictor"),
        "PsA" to Names("南鱼座", "Piscis Austrinus"),
        "Psc" to Names("双鱼座", "Pisces"),
        "Pup" to Names("船尾座", "Puppis"),
        "Pyx" to Names("罗盘座", "Pyxis"),
        "Ret" to Names("网罟座", "Reticulum"),
        "Scl" to Names("玉夫座", "Sculptor"),
        "Sco" to Names("天蝎座", "Scorpius"),
        "Sct" to Names("盾牌座", "Scutum"),
        "Ser" to Names("巨蛇座", "Serpens"),
        "Sex" to Names("六分仪座", "Sextans"),
        "Sge" to Names("天箭座", "Sagitta"),
        "Sgr" to Names("人马座", "Sagittarius"),
        "Tau" to Names("金牛座", "Taurus"),
        "Tel" to Names("望远镜座", "Telescopium"),
        "TrA" to Names("南三角座", "Triangulum Australe"),
        "Tri" to Names("三角座", "Triangulum"),
        "Tuc" to Names("杜鹃座", "Tucana"),
        "UMa" to Names("大熊座", "Ursa Major"),
        "UMi" to Names("小熊座", "Ursa Minor"),
        "Vel" to Names("船帆座", "Vela"),
        "Vir" to Names("室女座", "Virgo"),
        "Vol" to Names("飞鱼座", "Volans"),
        "Vul" to Names("狐狸座", "Vulpecula"),
    )

    /** Every code this table covers: the full set of 88 IAU constellations. */
    val codes: Set<String> get() = NAMES.keys

    /** The localized name for [code], or null when the code is not an IAU code. */
    fun nameOrNull(code: String, english: Boolean): String? =
        NAMES[code]?.let { if (english) it.en else it.zh }

    /**
     * The name to show for [code] in the interface language; an
     * unknown code falls back to the code itself, never to a blank.
     */
    fun displayName(code: String, english: Boolean): String =
        nameOrNull(code, english) ?: code
}
