package net.discdd.bundletransport;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.core.StringContains.containsString;

import android.Manifest;

import androidx.test.espresso.matcher.ViewMatchers;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.rule.GrantPermissionRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class MainActivityTest {

    @Rule
    public ActivityScenarioRule<BundleTransportActivity> activityRule =
            new ActivityScenarioRule<>(BundleTransportActivity.class);
    @Rule
    public GrantPermissionRule neededPermissions = GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION);

    @Test
    public void testGrpcServerState() {
        // Wait for the GRPC server state to be updated in the UI
        onView(ViewMatchers.withId(R.id.title)).check(matches(withText(containsString("BundleTransport"))));
    }
}
