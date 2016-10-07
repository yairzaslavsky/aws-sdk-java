/*
 * Copyright 2011-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.amazonaws.retry;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.util.ValidationUtils;

import java.util.Random;

/**
 * This class includes a set of pre-defined backoff policies.
 * See this blog for more information on the different algorithms:
 * https://www.awsarchitectureblog.com/2015/03/backoff.html
 */
public class PredefinedBackoffStrategies {

    /** Default base sleep time (milliseconds) for non-throttled exceptions. **/
    static final int SDK_DEFAULT_BASE_DELAY = 100;

    /** Default base sleep time (milliseconds) for throttled exceptions. **/
    static final int SDK_DEFAULT_THROTTLED_BASE_DELAY = 500;

    /** Default maximum back-off time before retrying a request */
    static final int SDK_DEFAULT_MAX_BACKOFF_IN_MILLISECONDS = 20 * 1000;

    /** Default base sleep time for DynamoDB. **/
    static final int DYNAMODB_DEFAULT_BASE_DELAY = 25;

    /** Maximum retry limit.  Avoids integer overflow issues. **/
    private static final int MAX_RETRIES = 30;

    public static class FullJitterBackoffStrategy implements RetryPolicy.BackoffStrategy {

        private final int baseDelay;
        private final int maxBackoffTime;
        private final Random random = new Random();

        public FullJitterBackoffStrategy(final int baseDelay,
                                         final int maxBackoffTime) {
            this.baseDelay = ValidationUtils.assertIsPositive(baseDelay, "Base delay");
            this.maxBackoffTime = ValidationUtils.assertIsPositive(maxBackoffTime, "Max backoff");
        }

        @Override
        public long delayBeforeNextRetry(AmazonWebServiceRequest originalRequest,
                                         AmazonClientException exception,
                                         int retriesAttempted) {
            int ceil = (retriesAttempted > MAX_RETRIES) ? maxBackoffTime :
                    Math.min(baseDelay * (1 << retriesAttempted), maxBackoffTime);
            return random.nextInt(ceil + 1);
        }
    }

    public static class EqualJitterBackoffStrategy implements RetryPolicy.BackoffStrategy {

        private final int baseDelay;
        private final int maxBackoffTime;
        private final Random random = new Random();

        public EqualJitterBackoffStrategy(final int baseDelay,
                                          final int maxBackoffTime) {
            this.baseDelay = ValidationUtils.assertIsPositive(baseDelay, "Base delay");
            this.maxBackoffTime = ValidationUtils.assertIsPositive(maxBackoffTime, "Max backoff");
        }

        @Override
        public long delayBeforeNextRetry(AmazonWebServiceRequest originalRequest,
                                        AmazonClientException exception,
                                        int retriesAttempted) {
            int ceil = (retriesAttempted > MAX_RETRIES) ? maxBackoffTime
                    : Math.min(maxBackoffTime, baseDelay * (1 << retriesAttempted));
            return (ceil / 2) + random.nextInt((ceil / 2) + 1);
        }
    }

    public static class ExponentialBackoffStrategy implements RetryPolicy.BackoffStrategy {

        private final int baseDelay;
        private final int maxBackoffTime;

        public ExponentialBackoffStrategy(final int baseDelay,
                                          final int maxBackoffTime) {
            this.baseDelay = ValidationUtils.assertIsPositive(baseDelay, "Base delay");
            this.maxBackoffTime = ValidationUtils.assertIsPositive(maxBackoffTime, "Max backoff");
        }

        @Override
        public long delayBeforeNextRetry(AmazonWebServiceRequest originalRequest,
                                         AmazonClientException exception,
                                         int retriesAttempted) {
            long potentialWait = 1L << retriesAttempted * baseDelay;
            return (retriesAttempted > MAX_RETRIES) ? maxBackoffTime :
                    (potentialWait < 0  ? maxBackoffTime :
                    Math.min(potentialWait, maxBackoffTime));
        }
    }

    /** A private class that implements the default back-off strategy. **/
    static class SDKDefaultBackoffStrategy implements RetryPolicy.BackoffStrategy {

        private final RetryPolicy.BackoffStrategy fullJitterBackoffStrategy;
        private final RetryPolicy.BackoffStrategy equalJitterBackoffStrategy;

        SDKDefaultBackoffStrategy() {
            fullJitterBackoffStrategy = new PredefinedBackoffStrategies.FullJitterBackoffStrategy(
                    SDK_DEFAULT_BASE_DELAY, SDK_DEFAULT_MAX_BACKOFF_IN_MILLISECONDS);
            equalJitterBackoffStrategy = new PredefinedBackoffStrategies.EqualJitterBackoffStrategy(
                    SDK_DEFAULT_THROTTLED_BASE_DELAY, SDK_DEFAULT_MAX_BACKOFF_IN_MILLISECONDS);
        }

        SDKDefaultBackoffStrategy(final int baseDelay, final int throttledBaseDelay, final int maxBackoff) {
            fullJitterBackoffStrategy = new PredefinedBackoffStrategies.FullJitterBackoffStrategy(
                    baseDelay, maxBackoff);
            equalJitterBackoffStrategy = new PredefinedBackoffStrategies.EqualJitterBackoffStrategy(
                    throttledBaseDelay, maxBackoff);
        }

        /** {@inheritDoc} */
        @Override
        public final long delayBeforeNextRetry(AmazonWebServiceRequest originalRequest,
                                               AmazonClientException exception,
                                               int retriesAttempted) {
            /*
             * We use the full jitter scheme for non-throttled exceptions and the
             * equal jitter scheme for throttled exceptions.  This gives a preference
             * to quicker response and larger retry distribution for service errors
             * and guarantees a minimum delay for throttled exceptions.
             */
            if (exception instanceof AmazonServiceException
                    && RetryUtils.isThrottlingException((AmazonServiceException)exception)) {
                return equalJitterBackoffStrategy.delayBeforeNextRetry(originalRequest, exception, retriesAttempted);
            } else {
                return fullJitterBackoffStrategy.delayBeforeNextRetry(originalRequest, exception, retriesAttempted);
            }
        }
    }

}
