package net.discdd.bundleclient;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.Suppress;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;

@RunWith(AndroidJUnit4.class)
@SuppressWarnings("UnspecifiedRegisterReceiverFlag")
public class BundleClientActivityTest {

    @Rule
    public ActivityScenarioRule<BundleClientActivity> activityRule =
            new ActivityScenarioRule<>(BundleClientActivity.class);

    @Test
    public void testConnectButton() {

        onView(withId(R.id.connect_button)).perform(click());

        onView(withId(R.id.wifidirect_response_text)).check(matches(withText("Starting connection...\n")));
    }
}
