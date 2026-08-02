package top.jie65535.mirai.profile

object ProfileItemReferences {
    data class Entry(
        val reference: String,
        val item: UserProfileItem,
    )

    fun entries(profile: UserProfileSnapshot): List<Entry> = profile.items.mapIndexed { index, item ->
        Entry(reference = "P${index + 1}", item = item)
    }

    fun resolve(profile: UserProfileSnapshot, rawReference: String?): UserProfileItem? {
        val index = rawReference
            ?.trim()
            ?.let(REFERENCE_PATTERN::matchEntire)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: return null
        return profile.items.getOrNull(index - 1)
    }

    private val REFERENCE_PATTERN = Regex("P([1-9]\\d*)")
}
