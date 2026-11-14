package cordis.samples.android

object TestTags {
    const val DRAWER = "drawer"
    const val DRAWER_ITEM_SETTINGS = "drawer_item_settings"
    const val DRAWER_ITEM_DEBUG = "drawer_item_debug"
    const val NOTES_INPUT = "notes_input"
    const val NOTES_ADD = "notes_add"
    const val NOTES_LIST = "notes_list"
    const val WEATHER_TEMPERATURE = "weather_temperature"
    const val INFO_TITLE = "info_title"
    const val DEBUG_HEALTH = "debug_health"
    const val DEBUG_TREE = "debug_tree"

    fun drawerItem(id: String): String = "drawer_item_$id"

    fun settingsRow(key: String): String = "settings_row_$key"

    fun settingsSwitch(key: String): String = "settings_switch_$key"
}
