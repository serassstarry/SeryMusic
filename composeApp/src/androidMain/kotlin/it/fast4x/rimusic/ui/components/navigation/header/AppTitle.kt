package it.fast4x.rimusic.ui.components.navigation.header

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.media3.common.Player
import app.kreate.android.service.player.StatefulPlayer
import it.fast4x.rimusic.utils.DisposableListener
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.PI
import kotlin.math.sin
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import app.kreate.android.BuildConfig
import app.kreate.android.R
import app.kreate.android.drawable.AppIcon
import it.fast4x.rimusic.colorPalette
import it.fast4x.rimusic.enums.NavRoutes
import it.fast4x.rimusic.typography
import it.fast4x.rimusic.ui.components.themed.Button
import it.fast4x.rimusic.utils.semiBold
import me.knighthat.utils.Toaster

private fun appIconClickAction(
    navController: NavController,
    countToReveal: MutableIntState,
    context: Context
) {
    countToReveal.intValue++

    val message: String =
        when( countToReveal.intValue ) {
            10 -> {
                countToReveal.intValue = 0
                NavRoutes.gamePacman.navigateHere( navController )
                ""
            }
            3 -> "Do you like clicking? Then continue..."
            6 -> "Okay, you’re looking for something, keep..."
            9 -> "You are a number one, click and enjoy the surprise"
            else -> ""
        }
    if( message.isNotEmpty() )
        Toaster.n( message, Toast.LENGTH_LONG )
}

private fun appIconLongClickAction(
    navController: NavController,
    context: Context
) {
    Toaster.n( "You are a number one, click and enjoy the surprise", Toast.LENGTH_LONG )
    NavRoutes.gameSnake.navigateHere( navController )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppLogo(
    navController: NavController,
    context: Context
) {
    val countToReveal = remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val hop = remember { Animatable( 0f ) }
    val modifier = Modifier.combinedClickable(
        onClick = {
            appIconClickAction( navController, countToReveal, context )
            scope.launch {
                hop.animateTo( 1f, tween(140) )
                hop.animateTo( 0f, spring(dampingRatio = 0.3f, stiffness = 500f) )
            }
        },
        onLongClick = { appIconLongClickAction( navController, context ) }
    )

    // Sery bobs her head while music plays
    val player: StatefulPlayer = koinInject()
    var isPlaying by remember { mutableStateOf( player.isPlaying ) }
    player.DisposableListener {
        object : Player.Listener {
            override fun onIsPlayingChanged( playing: Boolean ) { isPlaying = playing }
        }
    }
    val dance = rememberInfiniteTransition( label = "logo" )
    val beat by dance.animateFloat( 0f, 1f, infiniteRepeatable(tween(860, easing = LinearEasing)), label = "beat" )
    val amount by animateFloatAsState( if( isPlaying ) 1f else 0f, tween(600), label = "amount" )

    Image(
        bitmap = AppIcon.Round.imageBitmap( LocalContext.current ),
        contentDescription = "App's icon",
        modifier = modifier
            .size( 36.dp )
            .graphicsLayer {
                val wave = sin( beat * 2 * PI ).toFloat()
                rotationZ = wave * 9f * amount + hop.value * -14f
                translationY = (-kotlin.math.abs( wave ) * 2.5f * amount - hop.value * 6f) * density
                val pump = 1f + kotlin.math.abs( wave ) * 0.05f * amount + hop.value * 0.12f
                scaleX = pump
                scaleY = pump
                transformOrigin = TransformOrigin( 0.5f, 0.9f )
            }
    )
}

@Composable
private fun AppLogoText( navController: NavController ) {
    BasicText(
        text = BuildConfig.APP_NAME,
        style = typography().l.semiBold.copy( color = AppBar.contentColor() ),
        maxLines = 1,
        modifier = Modifier.clickable {
            if ( NavRoutes.home.isHere( navController ) ) return@clickable

            // In short, navigates to [NavRoutes.home] then previous stacks
            // effectively make it the start again
            navController.navigate( NavRoutes.home.name ) {
                popUpTo( navController.graph.findStartDestination().id ) {
                    inclusive = true
                }

                launchSingleTop = true
            }
        }
    )
}

// START
@Composable
fun AppTitle(
    navController: NavController,
    context: Context
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy( 5.dp ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppLogo( navController, context )
        AppLogoText( navController )

        if(Preference.parentalControl())
            Button(
                iconId = R.drawable.shield_checkmark,
                color = AppBar.contentColor(),
                padding = 0.dp,
                size = 20.dp
            ).Draw()

        if (Preference.debugLog())
            BasicText(
                text = stringResource(R.string.info_debug_mode_enabled),
                style = TextStyle(
                    fontSize = typography().xxs.semiBold.fontSize,
                    fontWeight = typography().xxs.semiBold.fontWeight,
                    fontFamily = typography().xxs.semiBold.fontFamily,
                    color = colorPalette().red
                ),
                modifier = Modifier
                    .clickable {
                        Toaster.s( R.string.info_debug_mode_is_enabled )

                        NavRoutes.settings.navigateHere( navController )
                    }
            )
    }
// END
}