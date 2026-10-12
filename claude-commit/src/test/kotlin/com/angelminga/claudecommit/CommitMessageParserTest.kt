package com.angelminga.claudecommit

import org.junit.Assert.assertEquals
import org.junit.Test

class CommitMessageParserTest {

    @Test
    fun `formato esperado`() {
        val s = CommitMessageParser.parse(
            """
            TIPO: feat
            RAZON: Añade una funcionalidad nueva.
            MENSAJE:
            feat(login): agregar inicio de sesión con Google

            - Nuevo botón en la pantalla de login
            """.trimIndent()
        )
        assertEquals("feat", s.type)
        assertEquals("Añade una funcionalidad nueva.", s.reason)
        assertEquals("feat(login): agregar inicio de sesión con Google\n\n- Nuevo botón en la pantalla de login", s.message)
    }

    @Test
    fun `quita bloque de código con etiqueta de lenguaje`() {
        val s = CommitMessageParser.parse("```text\nTIPO: fix\nRAZON: Corrige un bug.\nMENSAJE:\nfix: evitar NPE al guardar\n```")
        assertEquals("fix", s.type)
        assertEquals("fix: evitar NPE al guardar", s.message)
    }

    @Test
    fun `quita bloque de código sin etiqueta`() {
        val s = CommitMessageParser.parse("```\nTIPO: docs\nRAZON: Solo README.\nMENSAJE:\ndocs: actualizar README\n```")
        assertEquals("docs", s.type)
        assertEquals("docs: actualizar README", s.message)
    }

    @Test
    fun `bloque de código solo alrededor del mensaje`() {
        val s = CommitMessageParser.parse("TIPO: chore\nRAZON: Mantenimiento.\nMENSAJE:\n```\nchore: limpiar logs\n```")
        assertEquals("chore: limpiar logs", s.message)
    }

    @Test
    fun `tipo y etiquetas sin distinguir mayúsculas`() {
        val s = CommitMessageParser.parse("tipo: Refactor\nRazón: Reorganiza código.\nmensaje: refactor: extraer servicio")
        assertEquals("refactor", s.type)
        assertEquals("Reorganiza código.", s.reason)
        assertEquals("refactor: extraer servicio", s.message)
    }

    @Test
    fun `sin formato deduce el tipo del título`() {
        val s = CommitMessageParser.parse("perf(db)!: cachear consultas frecuentes")
        assertEquals("perf", s.type)
        assertEquals("", s.reason)
        assertEquals("perf(db)!: cachear consultas frecuentes", s.message)
    }

    @Test
    fun `tipo no estándar`() {
        val s = CommitMessageParser.parse("TIPO: feature\nRAZON: x\nMENSAJE:\nfeature: algo")
        assertEquals("feature (no estándar)", s.type)
    }

    @Test
    fun `texto libre sin tipo`() {
        val s = CommitMessageParser.parse("Actualicé varias cosas")
        assertEquals("desconocido (no estándar)", s.type)
        assertEquals("Actualicé varias cosas", s.message)
    }
}
