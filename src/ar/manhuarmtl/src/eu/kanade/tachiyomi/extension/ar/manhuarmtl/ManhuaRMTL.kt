package eu.kanade.tachiyomi.extension.ar.manhuarmtl

import eu.kanade.tachiyomi.multisrc.madara.Madara
import keiyoushi.annotation.Source
import java.text.SimpleDateFormat
import java.util.Locale

@Source
class ManhuaRMTL(
    override val lang: String = "ar",
    override val id: Long = 0,
) : Madara(
        "ManhuaRMTL",
        "https://manhuarmtl.com",
        lang,
        SimpleDateFormat("MM/dd/yyyy", Locale.US),
    )
