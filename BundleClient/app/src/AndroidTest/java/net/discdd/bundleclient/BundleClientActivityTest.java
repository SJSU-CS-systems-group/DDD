package net.discdd.bundleclient;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.rule.GrantPermissionRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static org.hamcrest.core.StringContains.containsString;

import android.Manifest;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class BundleClientActivityTest {

    @Rule
    public ActivityScenarioRule<BundleClientActivity> activityRule =
            new ActivityScenarioRule<>(BundleClientActivity.class);
    @Rule
    public GrantPermissionRule neededPermissions = GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION,
                                                                             Manifest.permission.NEARBY_WIFI_DEVICES);
}
