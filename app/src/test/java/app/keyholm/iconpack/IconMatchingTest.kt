package app.keyholm.iconpack

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class IconMatchingTest {
    private fun icon(
        name: String,
        issuer: List<String> = emptyList(),
    ) = IconDefinition(filename = "icons/$name.svg", name = name, issuer = issuer)

    private val index =
        iconIndex(
            IconPackDefinition(
                name = "test pack",
                version = 1,
                icons =
                    listOf(
                        icon("GitHub"),
                        icon("Google"),
                        icon("Microsoft"),
                        icon("eBay"),
                        icon("HP"),
                        icon("LINE"),
                        icon("Web"),
                        icon("Battle.net", issuer = listOf("blizzard")),
                        icon("Amazon Web Services", issuer = listOf("AWS")),
                        IconDefinition(filename = "icons/Raster.png", name = "Raster"),
                    ),
            ),
        )

    private fun match(registrableDomain: String) = matchIconEntry(index, registrableDomain)

    @Test
    fun `the registrable label matches an icon name case-insensitively`() {
        assertThat(match("GitHub.com")).isEqualTo("icons/GitHub.svg")
    }

    @Test
    fun `country suffixes don't hide the match`() {
        assertThat(match("ebay.co.uk")).isEqualTo("icons/eBay.svg")
    }

    @Test
    fun `a long name matches as a prefix`() {
        assertThat(match("microsoftonline.com")).isEqualTo("icons/Microsoft.svg")
    }

    @Test
    fun `a name inside the label but not at its start doesn't match`() {
        assertThat(match("pipeline.com")).isNull()
    }

    @Test
    fun `a short name only matches a whole label`() {
        assertThat(match("webauthn.io")).isNull()
        assertThat(match("hp.com")).isEqualTo("icons/HP.svg")
    }

    @Test
    fun `punctuation in a name is ignored`() {
        assertThat(match("battle.net")).isEqualTo("icons/Battle.net.svg")
    }

    @Test
    fun `issuers are matched as well as names`() {
        assertThat(match("blizzard.com")).isEqualTo("icons/Battle.net.svg")
        assertThat(match("aws.com")).isEqualTo("icons/Amazon Web Services.svg")
    }

    @Test
    fun `an unknown domain has no icon`() {
        assertThat(match("example.com")).isNull()
    }

    @Test
    fun `formats other than svg are left out`() {
        assertThat(match("raster.com")).isNull()
    }
}
