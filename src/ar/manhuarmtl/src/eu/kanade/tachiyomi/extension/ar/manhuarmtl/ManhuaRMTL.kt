package eu.kanade.tachiyomi.extension.ar.manhuarmtl

import eu.kanade.tachiyomi.multisrc.madara.Madara
import keiyoushi.annotation.Source
import java.text.SimpleDateFormat
import java.util.Locale

@Source
class ManhuaRMTL :
    Madara(
        "ManhuaRMTL",
        "https://manhuarmtl.com",
        "ar",
        SimpleDateFormat("MM/dd/yyyy", Locale.US),
    )
