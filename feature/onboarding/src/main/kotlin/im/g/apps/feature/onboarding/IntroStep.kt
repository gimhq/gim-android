package im.g.apps.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import im.g.apps.core.designsystem.component.GimPreviews
import im.g.apps.core.designsystem.explainer.AccountExplainerPointContent
import im.g.apps.core.designsystem.explainer.accountIntroPoints
import im.g.apps.core.designsystem.theme.GimTheme
import kotlinx.coroutines.launch

/**
 * What gim is, one [accountIntroPoints] entry per page. Skip shows on every page; Next moves on, and Get started
 * on the last page finishes like Skip. Each page scrolls on its own so large fonts are never clipped.
 */
@Composable
internal fun IntroStep(callbacks: OnboardingCallbacks, modifier: Modifier = Modifier) {
    val pages = accountIntroPoints
    val pagerState = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == pages.lastIndex
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        TextButton(
            onClick = callbacks.onFinishIntro,
            modifier = Modifier
                .align(Alignment.End)
                .testTag(OnboardingTestTags.INTRO_SKIP),
        ) {
            Text(stringResource(R.string.onboarding_intro_skip))
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { page ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 16.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                AccountExplainerPointContent(point = pages[page], modifier = Modifier.fillMaxWidth())
            }
        }
        PageDots(
            current = pagerState.currentPage,
            count = pages.size,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(vertical = 16.dp),
        )
        if (isLastPage) {
            Button(
                onClick = callbacks.onFinishIntro,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(OnboardingTestTags.INTRO_START),
            ) {
                Text(stringResource(R.string.onboarding_intro_start))
            }
        } else {
            Button(
                onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(OnboardingTestTags.INTRO_NEXT),
            ) {
                Text(stringResource(R.string.onboarding_intro_next))
            }
        }
    }
}

/** One dot per page, the current one filled; TalkBack reads "Page 1 of 3". */
@Composable
private fun PageDots(current: Int, count: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.onboarding_intro_page, current + 1, count)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.semantics { contentDescription = description },
    ) {
        repeat(count) { page ->
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = if (page == current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape,
                    ),
            )
        }
    }
}

@GimPreviews
@Composable
private fun OnboardingIntroPreview() {
    GimTheme {
        Surface {
            IntroStep(previewCallbacks)
        }
    }
}
