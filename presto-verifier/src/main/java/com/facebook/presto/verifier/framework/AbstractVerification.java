/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.verifier.framework;

import com.facebook.airlift.log.Logger;
import com.facebook.presto.jdbc.QueryStats;
import com.facebook.presto.sql.SqlFormatter;
import com.facebook.presto.sql.tree.Statement;
import com.facebook.presto.verifier.event.DeterminismAnalysisDetails;
import com.facebook.presto.verifier.event.QueryInfo;
import com.facebook.presto.verifier.event.VerifierQueryEvent;
import com.facebook.presto.verifier.event.VerifierQueryEvent.EventStatus;
import com.facebook.presto.verifier.framework.MatchResult.MatchType;
import com.facebook.presto.verifier.prestoaction.PrestoAction;
import com.facebook.presto.verifier.resolver.FailureResolverManager;
import com.facebook.presto.verifier.rewrite.QueryRewriter;
import io.airlift.units.Duration;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static com.facebook.presto.spi.StandardErrorCode.EXCEEDED_TIME_LIMIT;
import static com.facebook.presto.verifier.event.VerifierQueryEvent.EventStatus.FAILED;
import static com.facebook.presto.verifier.event.VerifierQueryEvent.EventStatus.FAILED_RESOLVED;
import static com.facebook.presto.verifier.event.VerifierQueryEvent.EventStatus.SKIPPED;
import static com.facebook.presto.verifier.event.VerifierQueryEvent.EventStatus.SUCCEEDED;
import static com.facebook.presto.verifier.framework.ClusterType.CONTROL;
import static com.facebook.presto.verifier.framework.ClusterType.TEST;
import static com.facebook.presto.verifier.framework.DataVerificationUtil.setupAndRun;
import static com.facebook.presto.verifier.framework.DataVerificationUtil.teardownSafely;
import static com.facebook.presto.verifier.framework.QueryState.FAILED_TO_SETUP;
import static com.facebook.presto.verifier.framework.QueryState.NOT_RUN;
import static com.facebook.presto.verifier.framework.QueryState.TIMED_OUT;
import static com.facebook.presto.verifier.framework.SkippedReason.CONTROL_QUERY_FAILED;
import static com.facebook.presto.verifier.framework.SkippedReason.CONTROL_QUERY_TIMED_OUT;
import static com.facebook.presto.verifier.framework.SkippedReason.CONTROL_SETUP_QUERY_FAILED;
import static com.facebook.presto.verifier.framework.SkippedReason.FAILED_BEFORE_CONTROL_QUERY;
import static com.facebook.presto.verifier.framework.SkippedReason.NON_DETERMINISTIC;
import static com.facebook.presto.verifier.framework.VerifierUtil.runAndConsume;
import static com.facebook.presto.verifier.prestoaction.PrestoExceptionClassifier.shouldResubmit;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Throwables.getStackTraceAsString;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

public abstract class AbstractVerification
        implements Verification
{
    private static final Logger log = Logger.get(AbstractVerification.class);

    private final PrestoAction prestoAction;
    private final SourceQuery sourceQuery;
    private final QueryRewriter queryRewriter;
    private final DeterminismAnalyzer determinismAnalyzer;
    private final FailureResolverManager failureResolverManager;
    private final VerificationContext verificationContext;

    private final String testId;
    private final boolean runTearDownOnResultMismatch;
    private final int verificationResubmissionLimit;

    public AbstractVerification(
            PrestoAction prestoAction,
            SourceQuery sourceQuery,
            QueryRewriter queryRewriter,
            DeterminismAnalyzer determinismAnalyzer,
            FailureResolverManager failureResolverManager,
            VerificationContext verificationContext,
            VerifierConfig verifierConfig)
    {
        this.prestoAction = requireNonNull(prestoAction, "prestoAction is null");
        this.sourceQuery = requireNonNull(sourceQuery, "sourceQuery is null");
        this.queryRewriter = requireNonNull(queryRewriter, "queryRewriter is null");
        this.determinismAnalyzer = requireNonNull(determinismAnalyzer, "determinismAnalyzer is null");
        this.failureResolverManager = requireNonNull(failureResolverManager, "failureResolverManager is null");
        this.verificationContext = requireNonNull(verificationContext, "verificationContext is null");

        this.testId = requireNonNull(verifierConfig.getTestId(), "testId is null");
        this.runTearDownOnResultMismatch = verifierConfig.isRunTeardownOnResultMismatch();
        this.verificationResubmissionLimit = verifierConfig.getVerificationResubmissionLimit();
    }

    protected abstract MatchResult verify(QueryBundle control, QueryBundle test, ChecksumQueryContext controlContext, ChecksumQueryContext testContext);

    protected PrestoAction getPrestoAction()
    {
        return prestoAction;
    }

    @Override
    public SourceQuery getSourceQuery()
    {
        return sourceQuery;
    }

    @Override
    public VerificationContext getVerificationContext()
    {
        return verificationContext;
    }

    @Override
    public VerificationResult run()
    {
        boolean resultMismatched = false;

        AtomicReference<QueryBundle> control = new AtomicReference<>();
        AtomicReference<QueryBundle> test = new AtomicReference<>();
        AtomicReference<QueryStats> controlStats = new AtomicReference<>();
        AtomicReference<QueryStats> testStats = new AtomicReference<>();
        AtomicReference<QueryState> controlState = new AtomicReference<>(NOT_RUN);
        AtomicReference<QueryState> testState = new AtomicReference<>(NOT_RUN);
        ChecksumQueryContext controlChecksumQueryContext = new ChecksumQueryContext();
        ChecksumQueryContext testChecksumQueryContext = new ChecksumQueryContext();
        Optional<MatchResult> matchResult = Optional.empty();
        Optional<DeterminismAnalysis> determinismAnalysis = Optional.empty();
        DeterminismAnalysisDetails.Builder determinismAnalysisDetails = DeterminismAnalysisDetails.builder();

        try {
            // Rewrite queries
            control.set(queryRewriter.rewriteQuery(sourceQuery.getControlQuery(), CONTROL));
            test.set(queryRewriter.rewriteQuery(sourceQuery.getTestQuery(), TEST));

            // Run queries
            runAndConsume(
                    () -> setupAndRun(prestoAction, control.get(), false),
                    controlStats::set,
                    e -> controlState.set(getFailingQueryState(e)));
            controlState.set(QueryState.SUCCEEDED);
            runAndConsume(
                    () -> setupAndRun(prestoAction, test.get(), false),
                    testStats::set,
                    e -> testState.set(getFailingQueryState(e)));
            testState.set(QueryState.SUCCEEDED);

            // Verify results
            matchResult = Optional.of(verify(control.get(), test.get(), controlChecksumQueryContext, testChecksumQueryContext));

            // Determinism analysis
            if (matchResult.get().isMismatchPossiblyCausedByNonDeterminism()) {
                determinismAnalysis = Optional.of(determinismAnalyzer.analyze(control.get(), matchResult.get().getControlChecksum(), determinismAnalysisDetails));
            }
            boolean maybeDeterministic = !determinismAnalysis.isPresent() ||
                    determinismAnalysis.get().isDeterministic() ||
                    determinismAnalysis.get().isUnknown();
            resultMismatched = maybeDeterministic && !matchResult.get().isMatched();

            return concludeVerification(
                    toOptional(control),
                    toOptional(test),
                    toOptional(controlStats),
                    toOptional(testStats),
                    controlState.get(),
                    testState.get(),
                    matchResult,
                    determinismAnalysis,
                    controlChecksumQueryContext,
                    testChecksumQueryContext,
                    determinismAnalysisDetails.build(),
                    Optional.empty());
        }
        catch (QueryException e) {
            return concludeVerification(
                    toOptional(control),
                    toOptional(test),
                    toOptional(controlStats),
                    toOptional(testStats),
                    controlState.get(),
                    testState.get(),
                    matchResult,
                    determinismAnalysis,
                    controlChecksumQueryContext,
                    testChecksumQueryContext,
                    determinismAnalysisDetails.build(),
                    Optional.of(e));
        }
        catch (Throwable t) {
            log.error(t);
            return new VerificationResult(this, false, Optional.empty());
        }
        finally {
            if (!resultMismatched || runTearDownOnResultMismatch) {
                teardownSafely(prestoAction, toOptional(control));
                teardownSafely(prestoAction, toOptional(test));
            }
        }
    }

    private VerificationResult concludeVerification(
            Optional<QueryBundle> control,
            Optional<QueryBundle> test,
            Optional<QueryStats> controlStats,
            Optional<QueryStats> testStats,
            QueryState controlState,
            QueryState testState,
            Optional<MatchResult> matchResult,
            Optional<DeterminismAnalysis> determinismAnalysis,
            ChecksumQueryContext controlChecksumQueryContext,
            ChecksumQueryContext testChecksumQueryContext,
            DeterminismAnalysisDetails determinismAnalysisDetails,
            Optional<QueryException> queryException)
    {
        if (queryException.isPresent()
                && shouldResubmit(queryException.get())
                && verificationContext.getResubmissionCount() < verificationResubmissionLimit) {
            return new VerificationResult(this, true, Optional.empty());
        }

        Optional<SkippedReason> skippedReason = getSkippedReason(controlState, determinismAnalysis);
        Optional<String> resolveMessage = Optional.empty();
        if (queryException.isPresent() && controlState == QueryState.SUCCEEDED) {
            checkState(controlStats.isPresent(), "controlQueryStats is missing");
            resolveMessage = failureResolverManager.resolve(controlStats.get(), queryException.get(), test);
        }

        EventStatus status;
        if (skippedReason.isPresent()) {
            status = SKIPPED;
        }
        else if (resolveMessage.isPresent()) {
            status = FAILED_RESOLVED;
        }
        else if (matchResult.isPresent() && matchResult.get().isMatched()) {
            status = SUCCEEDED;
        }
        else {
            status = FAILED;
        }

        Optional<String> errorCode = Optional.empty();
        Optional<String> errorMessage = Optional.empty();
        if (status != SUCCEEDED) {
            errorCode = Optional.ofNullable(queryException.map(QueryException::getErrorCodeName)
                    .orElse(matchResult.map(MatchResult::getMatchType)
                            .map(MatchType::name)
                            .orElse(null)));
            errorMessage = Optional.of(constructErrorMessage(queryException, matchResult, controlState, testState));
        }

        VerifierQueryEvent event = new VerifierQueryEvent(
                sourceQuery.getSuite(),
                testId,
                sourceQuery.getName(),
                status,
                skippedReason,
                determinismAnalysis,
                determinismAnalysis.isPresent() ?
                        Optional.of(determinismAnalysisDetails) :
                        Optional.empty(),
                resolveMessage,
                buildQueryInfo(
                        sourceQuery.getControlConfiguration(),
                        sourceQuery.getControlQuery(),
                        controlChecksumQueryContext,
                        control,
                        controlStats),
                buildQueryInfo(
                        sourceQuery.getTestConfiguration(),
                        sourceQuery.getTestQuery(),
                        testChecksumQueryContext,
                        test,
                        testStats),
                errorCode,
                errorMessage,
                queryException.map(QueryException::toQueryFailure),
                verificationContext.getQueryFailures(),
                verificationContext.getResubmissionCount());
        return new VerificationResult(this, false, Optional.of(event));
    }

    private static QueryInfo buildQueryInfo(
            QueryConfiguration configuration,
            String originalQuery,
            ChecksumQueryContext checksumQueryContext,
            Optional<QueryBundle> queryBundle,
            Optional<QueryStats> queryStats)
    {
        return new QueryInfo(
                configuration.getCatalog(),
                configuration.getSchema(),
                originalQuery,
                queryStats.map(QueryStats::getQueryId),
                checksumQueryContext.getChecksumQueryId(),
                queryBundle.map(QueryBundle::getQuery).map(AbstractVerification::formatSql),
                queryBundle.map(QueryBundle::getSetupQueries).map(AbstractVerification::formatSqls),
                queryBundle.map(QueryBundle::getTeardownQueries).map(AbstractVerification::formatSqls),
                checksumQueryContext.getChecksumQuery(),
                millisToSeconds(queryStats.map(QueryStats::getCpuTimeMillis)),
                millisToSeconds(queryStats.map(QueryStats::getWallTimeMillis)));
    }

    protected static String formatSql(Statement statement)
    {
        return SqlFormatter.formatSql(statement, Optional.empty());
    }

    protected static List<String> formatSqls(List<Statement> statements)
    {
        return statements.stream()
                .map(AbstractVerification::formatSql)
                .collect(toImmutableList());
    }

    private Optional<SkippedReason> getSkippedReason(QueryState controlState, Optional<DeterminismAnalysis> determinismAnalysis)
    {
        switch (controlState) {
            case FAILED:
                return Optional.of(CONTROL_QUERY_FAILED);
            case FAILED_TO_SETUP:
                return Optional.of(CONTROL_SETUP_QUERY_FAILED);
            case TIMED_OUT:
                return Optional.of(CONTROL_QUERY_TIMED_OUT);
            case NOT_RUN:
                return Optional.of(FAILED_BEFORE_CONTROL_QUERY);
        }
        if (determinismAnalysis.isPresent() && determinismAnalysis.get().isNonDeterministic()) {
            return Optional.of(NON_DETERMINISTIC);
        }
        return Optional.empty();
    }

    private static Optional<Double> millisToSeconds(Optional<Long> millis)
    {
        return millis.map(value -> new Duration(value, MILLISECONDS).getValue(SECONDS));
    }

    private static QueryState getFailingQueryState(QueryException queryException)
    {
        QueryStage queryStage = queryException.getQueryStage();
        checkArgument(
                queryStage.isSetup() || queryStage.isMain(),
                "Expect QueryStage SETUP or MAIN: %s",
                queryStage);

        if (queryStage.isSetup()) {
            return FAILED_TO_SETUP;
        }
        return queryException instanceof PrestoQueryException
                && ((PrestoQueryException) queryException).getErrorCode().equals(Optional.of(EXCEEDED_TIME_LIMIT)) ?
                TIMED_OUT :
                QueryState.FAILED;
    }

    private String constructErrorMessage(
            Optional<QueryException> queryException,
            Optional<MatchResult> matchResult,
            QueryState controlState,
            QueryState testState)
    {
        StringBuilder message = new StringBuilder(format("Test state %s, Control state %s.\n\n", testState, controlState));
        queryException.ifPresent(e -> message.append(e.getQueryStage().name().replace("_", " "))
                .append(" query failed on ")
                .append(e.getQueryStage().getTargetCluster())
                .append(" cluster:\n")
                .append(getStackTraceAsString(e.getCause())));
        matchResult.ifPresent(result -> message.append(result.getResultsComparison()));
        return message.toString();
    }

    private static <T> Optional<T> toOptional(AtomicReference<T> reference)
    {
        return Optional.ofNullable(reference.get());
    }
}
