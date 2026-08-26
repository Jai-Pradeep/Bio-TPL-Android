package com.example.face_auth_app

import android.content.Context
import org.json.JSONObject
import kotlin.math.sqrt

class FaceAuthVerifier(context: Context) {

    private val knownUserTemplate: FloatArray
    private val professorTemplate: FloatArray

    private val knownUserThreshold: Float
    private val professorThreshold: Float

    init {

        val jsonText =
            context.assets
                .open("face_auth_model.json")
                .bufferedReader()
                .use { it.readText() }

        val json = JSONObject(jsonText)

        knownUserThreshold =
            json.getDouble("known_user_threshold").toFloat()

        professorThreshold =
            json.getDouble("professor_threshold").toFloat()

        // -------------------------------
        // Class A template
        // -------------------------------

        val knownArray =
            json.getJSONArray("known_user_template")

        knownUserTemplate =
            FloatArray(knownArray.length()) { i ->
                knownArray.getDouble(i).toFloat()
            }

        // -------------------------------
        // Class B template
        // -------------------------------

        val professorArray =
            json.getJSONArray("professor_template")

        professorTemplate =
            FloatArray(professorArray.length()) { i ->
                professorArray.getDouble(i).toFloat()
            }
    }

    private fun cosineSimilarity(
        a: FloatArray,
        b: FloatArray
    ): Float {

        var dot = 0.0
        var normA = 0.0
        var normB = 0.0

        for (i in a.indices) {

            val x = a[i].toDouble()
            val y = b[i].toDouble()

            dot += x * y
            normA += x * x
            normB += y * y
        }

        return (
                dot /
                        (sqrt(normA) * sqrt(normB))
                ).toFloat()
    }

    fun classify(
        embedding: FloatArray
    ): Result {

        val knownScore =
            cosineSimilarity(
                embedding,
                knownUserTemplate
            )

        val professorScore =
            cosineSimilarity(
                embedding,
                professorTemplate
            )

        val knownValid =
            knownScore >= knownUserThreshold

        val professorValid =
            professorScore >= professorThreshold

        val label =
            when {

                knownValid && professorValid -> {

                    if (knownScore >= professorScore) {
                        "Known User"
                    } else {
                        "Professor"
                    }
                }

                knownValid -> {
                    "Known User"
                }

                professorValid -> {
                    "Professor"
                }

                else -> {
                    "Unknown User"
                }
            }

        return Result(
            label = label,
            knownScore = knownScore,
            professorScore = professorScore
        )
    }

    fun getKnownThreshold(): Float {
        return knownUserThreshold
    }

    fun getProfessorThreshold(): Float {
        return professorThreshold
    }

    data class Result(
        val label: String,
        val knownScore: Float,
        val professorScore: Float
    )
}