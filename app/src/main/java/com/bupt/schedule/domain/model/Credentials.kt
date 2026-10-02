package com.bupt.schedule.domain.model

data class Credentials(
    val account: String,
    val password: String,
    val teachingCloudPassword: String? = null,
) {
    fun forTeachingLogin(): Credentials = copy(teachingCloudPassword = null)
}
