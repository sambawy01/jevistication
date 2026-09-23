package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImpersonationTest {

    private val mum = Contact("Mom", setOf("mom@family.example"))
    private val contacts = listOf(mum)
    private val history = listOf(
        Message("Mom", "mom@family.example", "call me"),
        Message("Bank", "alerts@bank.example", "statement ready"),
    )

    private fun reasons(message: Message) =
        Impersonation.check(message, contacts, history).map { it.reason }.toSet()

    @Test
    fun `a known name from an unknown address is the core signal`() {
        val found = reasons(Message("Mom", "mom@totally-different.example", "send me a gift card"))
        assertTrue(ImpersonationReason.KNOWN_NAME_UNKNOWN_ADDRESS in found)
    }

    @Test
    fun `the real contact raises nothing`() {
        assertTrue(Impersonation.check(history[0], contacts, history).isEmpty())
    }

    @Test
    fun `spots a near-miss of a domain the contact really uses`() {
        val found = reasons(Message("Mom", "mom@famlly.example", "urgent"))
        assertTrue(ImpersonationReason.LOOKALIKE_DOMAIN in found)
    }

    @Test
    fun `a wholly unrelated domain is not called a lookalike`() {
        val found = reasons(Message("Mom", "mom@zzzzzzzzzz.example", "urgent"))
        assertTrue(ImpersonationReason.KNOWN_NAME_UNKNOWN_ADDRESS in found)
        assertTrue(ImpersonationReason.LOOKALIKE_DOMAIN !in found)
    }

    @Test
    fun `spots a homograph domain`() {
        val found = reasons(Message("Mom", "mom@fаmily.example", "urgent")) // Cyrillic a
        assertTrue(ImpersonationReason.HOMOGRAPH_IN_ADDRESS in found)
    }

    @Test
    fun `notes a first contact from an unseen address`() {
        val found = reasons(Message("Delivery Co", "notice@parcel.example", "reschedule"))
        assertTrue(ImpersonationReason.FIRST_CONTACT_FROM_ADDRESS in found)
    }

    @Test
    fun `an unknown sender already in history is not a first contact`() {
        val found = reasons(Message("Bank", "alerts@bank.example", "another statement"))
        assertTrue(ImpersonationReason.FIRST_CONTACT_FROM_ADDRESS !in found)
    }

    @Test
    fun `matches a contact name regardless of case`() {
        val found = reasons(Message("mom", "mom@elsewhere.example", "hi"))
        assertTrue(ImpersonationReason.KNOWN_NAME_UNKNOWN_ADDRESS in found)
    }

    @Test
    fun `edit distance underpins the lookalike check`() {
        assertEquals(0, Impersonation.levenshtein("family.example", "family.example"))
        assertEquals(1, Impersonation.levenshtein("family", "famlly"))
        assertEquals(3, Impersonation.levenshtein("abc", "xyz"))
    }
}
