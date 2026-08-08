package coredevices.coreapp.ui.screens

import androidx.compose.ui.text.AnnotatedString
import coredevices.util.Permission
import coredevices.util.description

// No permission-specific onboarding copy on desktop yet - falls back to the generic description
// used by every other unhandled permission, same as Android's own else branch.
actual fun Permission.descriptionOnboarding(): AnnotatedString = AnnotatedString(description())
