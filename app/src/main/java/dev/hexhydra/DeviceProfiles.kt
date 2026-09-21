package dev.hexhydra

data class DeviceEntry(val manufacturer: String, val model: String, val codename: String, val product: String)

object DeviceProfiles {
    val entries = listOf(
        // Google Pixel (Android 15)
        DeviceEntry("Google", "Pixel 9 Pro XL", "komodo", "komodo"),
        DeviceEntry("Google", "Pixel 9 Pro", "caiman", "caiman"),
        DeviceEntry("Google", "Pixel 9", "tokay", "tokay"),
        DeviceEntry("Google", "Pixel 9 Pro Fold", "comet", "comet"),
        DeviceEntry("Google", "Pixel 9a", "tegu", "tegu"),
        DeviceEntry("Google", "Pixel 8 Pro", "husky", "husky"),
        DeviceEntry("Google", "Pixel 8", "shiba", "shiba"),
        DeviceEntry("Google", "Pixel 8a", "akita", "akita"),
        DeviceEntry("Google", "Pixel Fold", "felix", "felix"),
        DeviceEntry("Google", "Pixel Tablet", "tangorpro", "tangorpro"),

        // Samsung (6 models only)
        DeviceEntry("Samsung", "Galaxy S25 Ultra", "s5q", "s5q"),
        DeviceEntry("Samsung", "Galaxy S25+", "s5qp", "s5qp"),
        DeviceEntry("Samsung", "Galaxy S25", "s5qr", "s5qr"),
        DeviceEntry("Samsung", "Galaxy S24 Ultra", "e3q", "e3q"),
        DeviceEntry("Samsung", "Galaxy Z Fold 6", "q6q", "q6q"),
        DeviceEntry("Samsung", "Galaxy Z Flip 6", "b6q", "b6q"),

        // OnePlus
        DeviceEntry("OnePlus", "OnePlus 13", "pjz110", "pjz110"),
        DeviceEntry("OnePlus", "OnePlus 13R", "cph2645", "cph2645"),
        DeviceEntry("OnePlus", "OnePlus 12", "wly", "wly"),
        DeviceEntry("OnePlus", "OnePlus Open", "cph2551", "cph2551"),

        // Xiaomi
        DeviceEntry("Xiaomi", "Xiaomi 15 Ultra", "xuanya", "xuanya"),
        DeviceEntry("Xiaomi", "Xiaomi 15", "dada", "dada"),
        DeviceEntry("Xiaomi", "Xiaomi 14T Pro", "degas", "degas"),
        DeviceEntry("Xiaomi", "POCO F7 Pro", "zorn", "zorn"),

        // Nothing
        DeviceEntry("Nothing", "Nothing Phone (3)", "Arcanine", "Arcanine"),
        DeviceEntry("Nothing", "Nothing Phone (3a)", "Alakazam3", "Alakazam3"),
        DeviceEntry("Nothing", "Nothing Phone (3a) Pro", "Alakazam3P", "Alakazam3P"),

        // Motorola
        DeviceEntry("Motorola", "Edge 50 Ultra", "eqs", "eqs"),
        DeviceEntry("Motorola", "Edge 50 Pro", "mac24", "mac24"),
        DeviceEntry("Motorola", "Razr 50 Ultra", "ginna", "ginna"),

        // Asus
        DeviceEntry("Asus", "ROG Phone 9 Pro", "ai2501", "ai2501"),
        DeviceEntry("Asus", "Zenfone 12 Ultra", "ai2502", "ai2502"),

        // Sony
        DeviceEntry("Sony", "Xperia 1 VI", "pdx234", "pdx234"),
        DeviceEntry("Sony", "Xperia 5 VI", "pdx244", "pdx244"),

        // Oppo
        DeviceEntry("Oppo", "Find X8 Pro", "cph2659", "cph2659"),
        DeviceEntry("Oppo", "Find N3", "cph2499", "cph2499"),

        // Honor
        DeviceEntry("Honor", "Magic 7 Pro", "bvl-an20", "bvl-an20"),
        DeviceEntry("Honor", "Magic V3", "fme-an00", "fme-an00"),

        // Realme
        DeviceEntry("Realme", "GT 7 Pro", "rmx5010", "rmx5010"),
        DeviceEntry("Realme", "14 Pro+", "rmx5056", "rmx5056"),

        // Nubia
        DeviceEntry("Nubia", "RedMagic 10 Pro", "nx789j", "nx789j"),

        // Fairphone
        DeviceEntry("Fairphone", "Fairphone 5", "fp5", "fp5")
    )
}
