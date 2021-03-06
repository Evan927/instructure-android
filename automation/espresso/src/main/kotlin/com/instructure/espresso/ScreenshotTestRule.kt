//
// Copyright (C) 2018-present Instructure, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//



package com.instructure.espresso

import android.support.test.InstrumentationRegistry
import android.support.test.espresso.Espresso
import android.support.test.espresso.base.DefaultFailureHandler
import com.instructure.espresso.matchers.WaitForCheckMatcher
import com.instructure.espresso.matchers.WaitForViewMatcher

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

import java.util.concurrent.atomic.AtomicBoolean

class ScreenshotTestRule : TestRule {

    // Run all test methods tryCount times. Take screenshots on failure.
    // A method rule would allow targeting specific (method.getAnnotation(Retry.class))
    private val tryCount = 2

    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            @Throws(Throwable::class)
            override fun evaluate() {
                var error: Throwable? = null

                val errorHandled = AtomicBoolean(false)

                // Espresso failure handler will capture accurate UI screenshots.
                // if we wait for `try { base.evaluate() } catch ()` then the UI will be in a different state
                //
                // Only espresso failures trigger the espresso failure handlers. For JUnit assert errors,
                // those must be captured in `try { base.evaluate() } catch ()`
                Espresso.setFailureHandler { throwable, matcher ->
                    // Don't save intermediate waitForView/waitForCheck screenshots
                    if (WaitForViewMatcher.finishedWaiting() &&
                            WaitForCheckMatcher.finishedWaiting() &&
                            ClickUntilMethod.finishedWaiting()) {
                        EspressoScreenshot.takeScreenshot(description)
                    }
                    errorHandled.set(true)
                    DefaultFailureHandler(InstrumentationRegistry.getTargetContext()).handle(throwable, matcher)
                }

                for (i in 0 until tryCount) {
                    errorHandled.set(false)
                    try {
                        base.evaluate()
                        return
                    } catch (t: Throwable) {
                        if (!errorHandled.get()) {
                            EspressoScreenshot.takeScreenshot(description)
                        }
                        error = t
                    }

                }

                if (error != null) throw error
            }
        }
    }
}
