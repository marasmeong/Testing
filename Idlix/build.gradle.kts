android {
    namespace = "com.idlix"
}

version = 9

cloudstream {
    setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/TheAlyss/cloudstream-AlyssRepo")
    description = "Idlix - Film dan Serial Subtitle Indonesia"
    authors = listOf("meong")
    language = "id"
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "AsianDrama")
}
