package app.keyholm.ui.createpasskey

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.keyholm.ui.theme.KeyholmTheme
import app.keyholm.webauthn.RpId
import app.keyholm.webauthn.WebAuthnAlgorithm
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val ATTESTATION_ROW = "Include attestation"
private const val DEVICE_PROPERTIES_ROW = "Include device properties"
private const val IDENTITY_ROW = "Identify as Keyholm"

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class CreationOptionsSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val choices = mutableListOf<CreationChoice>()

    private fun showSheet(
        algorithms: List<WebAuthnAlgorithm> = listOf(WebAuthnAlgorithm.ES256, WebAuthnAlgorithm.ED25519),
        attestation: OptionChoice = OptionChoice.Ask(initial = false),
        identity: OptionChoice = OptionChoice.Ask(initial = true),
        devicePropertiesAvailable: Boolean = true,
    ) {
        val prompt =
            CreationOptionsPrompt(
                rpId = RpId("example.com"),
                userName = "alice",
                algorithms = algorithms,
                attestation = attestation,
                identity = identity,
                devicePropertiesAvailable = devicePropertiesAvailable,
            )
        compose.setContent {
            KeyholmTheme(darkTheme = false) {
                CreationOptionsSheet(prompt = prompt, onChoice = { choices += it }, onDismiss = {})
            }
        }
    }

    private fun switchIn(rowTitle: String) = compose.onNode(isToggleable() and hasAnyAncestor(hasText(rowTitle)))

    @Test
    fun showsTheRequestAndEveryOfferedAlgorithm() {
        showSheet(algorithms = listOf(WebAuthnAlgorithm.ES256, WebAuthnAlgorithm.ML_DSA_65))

        compose.onNodeWithText("Create passkey to sign in as alice to example.com?").assertIsDisplayed()
        compose.onNodeWithText("ES256").assertIsDisplayed()
        compose.onNodeWithText("ML-DSA-65").assertIsDisplayed()
    }

    @Test
    fun picksTheTappedAlgorithmWithTheConfiguredDefaults() {
        showSheet()

        compose.onNodeWithText("Ed25519").performClick()

        assertThat(choices).containsExactly(
            CreationChoice(
                WebAuthnAlgorithm.ED25519,
                includeAttestation = false,
                includeDeviceProperties = false,
                identifyAsKeyholm = true,
            ),
        )
    }

    @Test
    fun carriesToggledOptionsIntoTheChoice() {
        showSheet()

        switchIn(ATTESTATION_ROW).performClick()
        switchIn(IDENTITY_ROW).performClick()
        compose.onNodeWithText("ES256").performClick()

        assertThat(choices).containsExactly(
            CreationChoice(
                WebAuthnAlgorithm.ES256,
                includeAttestation = true,
                includeDeviceProperties = false,
                identifyAsKeyholm = false,
            ),
        )
    }

    @Test
    fun disablesDevicePropertiesWhileAttestationIsOff() {
        showSheet()

        switchIn(DEVICE_PROPERTIES_ROW).assertIsNotEnabled()
        switchIn(DEVICE_PROPERTIES_ROW).assertIsOff()
        switchIn(ATTESTATION_ROW).performClick()
        switchIn(DEVICE_PROPERTIES_ROW).assertIsEnabled()
        switchIn(DEVICE_PROPERTIES_ROW).assertIsOff()
    }

    @Test
    fun keepsDevicePropertiesOffAndDisabledOnceTheyFailed() {
        showSheet(attestation = OptionChoice.Ask(initial = true), devicePropertiesAvailable = false)

        switchIn(ATTESTATION_ROW).assertIsOn()
        switchIn(DEVICE_PROPERTIES_ROW).assertIsNotEnabled()
        switchIn(DEVICE_PROPERTIES_ROW).assertIsOff()
        compose.onNodeWithText("ES256").performClick()

        assertThat(choices).containsExactly(
            CreationChoice(
                WebAuthnAlgorithm.ES256,
                includeAttestation = true,
                includeDeviceProperties = false,
                identifyAsKeyholm = true,
            ),
        )
    }

    @Test
    fun carriesDevicePropertiesIntoTheChoice() {
        showSheet()

        switchIn(ATTESTATION_ROW).performClick()
        switchIn(DEVICE_PROPERTIES_ROW).performClick()
        compose.onNodeWithText("ES256").performClick()

        assertThat(choices).containsExactly(
            CreationChoice(
                WebAuthnAlgorithm.ES256,
                includeAttestation = true,
                includeDeviceProperties = true,
                identifyAsKeyholm = true,
            ),
        )
    }

    @Test
    fun dropsDevicePropertiesWhenAttestationIsTurnedBackOff() {
        showSheet()

        switchIn(ATTESTATION_ROW).performClick()
        switchIn(DEVICE_PROPERTIES_ROW).performClick()
        switchIn(ATTESTATION_ROW).performClick()
        compose.onNodeWithText("ES256").performClick()

        assertThat(choices).containsExactly(
            CreationChoice(
                WebAuthnAlgorithm.ES256,
                includeAttestation = false,
                includeDeviceProperties = false,
                identifyAsKeyholm = true,
            ),
        )
    }

    @Test
    fun tappingTheRowBodyDrivesTheSameSwitch() {
        showSheet()

        switchIn(ATTESTATION_ROW).assertIsOff()
        compose.onNodeWithText(ATTESTATION_ROW).performClick()
        switchIn(ATTESTATION_ROW).assertIsOn()
    }

    @Test
    fun carriesFixedOptionsIntoTheChoiceWithoutShowingTheirRows() {
        showSheet(attestation = OptionChoice.Fixed(false), identity = OptionChoice.Fixed(false))

        compose.onNodeWithText(ATTESTATION_ROW).assertDoesNotExist()
        compose.onNodeWithText(DEVICE_PROPERTIES_ROW).assertDoesNotExist()
        compose.onNodeWithText(IDENTITY_ROW).assertDoesNotExist()

        compose.onNodeWithText("ES256").performClick()

        assertThat(choices).containsExactly(
            CreationChoice(WebAuthnAlgorithm.ES256, includeAttestation = false, includeDeviceProperties = false, identifyAsKeyholm = false),
        )
    }

    @Test
    fun explainsWhyEachQuestionIsBeingAsked() {
        showSheet()

        compose
            .onNodeWithText(
                "You've configured Keyholm to ask you which algorithm to use and whether to identify as Keyholm. " +
                    "Keyholm always asks whether to include attestation.",
            ).assertExists()
    }

    @Test
    fun explanationOmitsOptionsThatAreNotAsked() {
        showSheet(
            algorithms = listOf(WebAuthnAlgorithm.ES256),
            attestation = OptionChoice.Fixed(false),
        )

        compose
            .onNodeWithText("You've configured Keyholm to ask you whether to identify as Keyholm.")
            .assertExists()
    }
}
