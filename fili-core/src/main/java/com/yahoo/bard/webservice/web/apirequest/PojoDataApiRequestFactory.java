// Copyright 2017 Yahoo Inc.
// Licensed under the terms of the Apache license. Please see LICENSE.md file distributed with this work for terms.
package com.yahoo.bard.webservice.web.apirequest;

import static com.yahoo.bard.webservice.web.ErrorMessageFormat.INTEGER_INVALID;
import static com.yahoo.bard.webservice.web.ErrorMessageFormat.TABLE_UNDEFINED;
import static com.yahoo.bard.webservice.web.ErrorMessageFormat.TOP_N_UNSORTED;
import static com.yahoo.bard.webservice.web.apirequest.DataApiRequest.DATE_TIME_STRING;
import static com.yahoo.bard.webservice.web.apirequest.DefaultGranularityGenerators.generateGranularity;

import com.yahoo.bard.webservice.config.SystemConfig;
import com.yahoo.bard.webservice.config.SystemConfigProvider;
import com.yahoo.bard.webservice.data.DruidHavingBuilder;
import com.yahoo.bard.webservice.data.dimension.Dimension;
import com.yahoo.bard.webservice.data.dimension.DimensionDictionary;
import com.yahoo.bard.webservice.data.dimension.DimensionField;
import com.yahoo.bard.webservice.data.filterbuilders.DruidFilterBuilder;
import com.yahoo.bard.webservice.data.metric.LogicalMetric;
import com.yahoo.bard.webservice.data.metric.MetricDictionary;
import com.yahoo.bard.webservice.data.time.Granularity;
import com.yahoo.bard.webservice.data.time.GranularityParser;
import com.yahoo.bard.webservice.druid.model.having.Having;
import com.yahoo.bard.webservice.druid.model.orderby.OrderByColumn;
import com.yahoo.bard.webservice.druid.model.orderby.SortDirection;
import com.yahoo.bard.webservice.table.LogicalTable;
import com.yahoo.bard.webservice.table.LogicalTableDictionary;
import com.yahoo.bard.webservice.table.TableIdentifier;
import com.yahoo.bard.webservice.web.ApiHaving;
import com.yahoo.bard.webservice.web.BadApiRequestException;
import com.yahoo.bard.webservice.web.ResponseFormatType;
import com.yahoo.bard.webservice.web.filters.ApiFilters;
import com.yahoo.bard.webservice.web.util.PaginationParameters;

import org.joda.time.DateTimeZone;
import org.joda.time.Interval;
import org.joda.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.inject.Inject;
import javax.ws.rs.container.ContainerRequestContext;

/**
 * A factory implementation for default POJO DataApiRequests.
 */
public class PojoDataApiRequestFactory implements DataApiRequestFactory {

    private static final Logger LOG = LoggerFactory.getLogger(PojoDataApiRequestFactory.class);
    private static final SystemConfig SYSTEM_CONFIG = SystemConfigProvider.getInstance();

    // Default JodaTime zone to UTC
    private final DateTimeZone systemTimeZone = DateTimeZone.forID(SYSTEM_CONFIG.getStringProperty(
            SYSTEM_CONFIG.getPackageVariableName("timezone"),
            "UTC"
    ));

    private final LogicalTableDictionary logicalTableDictionary;
    private final DimensionDictionary dimensionDictionary;
    private final DruidFilterBuilder druidFilterBuilder;
    private final GranularityParser granularityParser;
    private final HavingGenerator havingGenerator;

    /**
     * Constructor.
     *
     * @param logicalTableDictionary  The logical table dictionary
     * @param dimensionDictionary  The system dimension dictionary
     * @param granularityParser  The parser for granularities
     * @param druidFilterBuilder  A builder function for druid filters
     * @param havingGenerator  A builder fuction for druid havings
     */
    @Inject
    public PojoDataApiRequestFactory(
            LogicalTableDictionary logicalTableDictionary,
            DimensionDictionary dimensionDictionary,
            GranularityParser granularityParser,
            DruidFilterBuilder druidFilterBuilder,
            HavingGenerator havingGenerator
    ) {
        this.logicalTableDictionary = logicalTableDictionary;
        this.dimensionDictionary = dimensionDictionary;
        this.granularityParser = granularityParser;
        this.druidFilterBuilder = druidFilterBuilder;
        this.havingGenerator = havingGenerator;
    }

    /**
     * Method to remove the dateTime column from map of columns and its direction.
     *
     * @param sortColumns  map of columns and its direction
     *
     * @return Map of columns and its direction without dateTime sort column
     */
    private Map<String, SortDirection> removeDateTimeSortColumn(Map<String, SortDirection> sortColumns) {
        if (sortColumns != null && sortColumns.containsKey(DATE_TIME_STRING)) {
            sortColumns.remove(DATE_TIME_STRING);
            return sortColumns;
        } else {
            return sortColumns;
        }
    }

    /**
     * Parses the requested input String by converting it to an integer, while treating null as zero.
     *
     * @param value  The requested integer value as String.
     * @param parameterName  The parameter name that corresponds to the requested integer value.
     *
     * @return The integer corresponding to {@code value} or zero if {@code value} is null.
     * @throws BadApiRequestException if the input String can not be parsed as an integer.
     */
    protected int generateInteger(String value, String parameterName) throws BadApiRequestException {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException nfe) {
            LOG.debug(INTEGER_INVALID.logFormat(value, parameterName), nfe);
            throw new BadApiRequestException(INTEGER_INVALID.logFormat(value, parameterName), nfe);
        }
    }

    @Override
    @SuppressWarnings({"checkstyle:methodlength"})
    public DataApiRequest buildDataApiRequest(
            DataApiRequestModel model,
            String asyncAfter,
            Optional<PaginationParameters> paginationParameters,
            ContainerRequestContext requestContext,
            MetricDictionary metricDictionary
    ) {

        DateTimeZone timeZone =
                DateAndTimeGenerators.generateTimeZone(model.getTimeZone(), systemTimeZone);

        // Time grain must be from allowed interval keywords
        Granularity granularity = generateGranularity(model.getTimeGrain(), timeZone, granularityParser);

        TableIdentifier tableId = new TableIdentifier(model.getTableName(), granularity);

        // Logical table must be in the logical table dictionary
        LogicalTable table = logicalTableDictionary.get(tableId);
        if (table == null) {
            LOG.debug(TABLE_UNDEFINED.logFormat(model.getTableName()));
            throw new BadApiRequestException(TABLE_UNDEFINED.format(model.getTableName()));
        }

        DateTimeFormatter dateTimeFormatter = DateAndTimeGenerators.generateDateTimeFormatter(timeZone);

        Set<Interval> intervals = DateAndTimeGenerators.generateIntervals(
                model.getIntervals(),
                granularity,
                dateTimeFormatter
        );

        // At least one logical metric is required
        Set<LogicalMetric> logicalMetrics = DefaultLogicalMetricsGenerators.generateLogicalMetrics(
                model.getMetrics(),
                metricDictionary,
                dimensionDictionary,
                table,
                druidFilterBuilder
        );
        ApiRequestValidators.validateMetrics(logicalMetrics, table);

        // Zero or more grouping dimensions may be specified
        Set<Dimension> dimensions = DefaultDimensionGenerators.generateDimensions(
                model.getDimensions(),
                dimensionDictionary
        );
        ApiRequestValidators.validateRequestDimensions(dimensions, table);

        // Map of dimension to its fields specified using show clause (matrix params)
        LinkedHashMap<Dimension, LinkedHashSet<DimensionField>> perDimensionFields =
                DefaultDimensionGenerators.generateDimensionFields(model.getDimensions(), dimensionDictionary);

        // Zero or more filtering dimensions may be referenced
        ApiFilters apiFilters = DefaultFilterGenerator.generateFilters(model.getFilters(), table, dimensionDictionary);


        // Zero or more having queries may be referenced
        Map<LogicalMetric, Set<ApiHaving>> havings = havingGenerator.apply(model.getHavings(), logicalMetrics);

        Having having = DruidHavingBuilder.buildHavings(havings);

        //Using the LinkedHashMap to preserve the sort order
        LinkedHashMap<String, SortDirection> sortColumnDirection =
                DefaultSortColumnGenerators.generateSortColumns(model.getSorts());

        //Requested sort on dateTime column
        Optional<OrderByColumn> dateTimeSort =
                DefaultSortColumnGenerators.generateDateTimeSortColumn(sortColumnDirection);

        // Requested sort on metrics - optional, can be empty Set
        LinkedHashSet<OrderByColumn> sorts = DefaultSortColumnGenerators.generateSortColumns(
                removeDateTimeSortColumn(sortColumnDirection),
                logicalMetrics,
                metricDictionary
        );

        // Overall requested number of rows in the response. Ignores grouping in time buckets.
        int count = generateInteger(model.getCount(), "count");

        // This is the validation part for count that is inlined here because currently it is very brief.
        if (count < 0) {
            LOG.debug(INTEGER_INVALID.logFormat(count, "count"));
            throw new BadApiRequestException(INTEGER_INVALID.logFormat(count, "count"));
        }

        // Requested number of rows per time bucket in the response
        int topN = generateInteger(model.getTopN(), "topN");

        // This is the validation part for topN that is inlined here because currently it is very brief.
        if (topN < 0) {
            LOG.debug(INTEGER_INVALID.logFormat(topN, "topN"));
            throw new BadApiRequestException(INTEGER_INVALID.logFormat(topN, "topN"));
        } else if (topN > 0 && sorts.isEmpty()) {
            LOG.debug(TOP_N_UNSORTED.logFormat(topN));
            throw new BadApiRequestException(TOP_N_UNSORTED.format(topN));
        }

        long asyncAfterValue = DefaultOutputFormatGenerators.generateAsyncAfter(
                asyncAfter == null ?
                        SYSTEM_CONFIG.getStringProperty(SYSTEM_CONFIG.getPackageVariableName("default_asyncAfter")) :
                        asyncAfter
        );

        ResponseFormatType format = DefaultOutputFormatGenerators.generateAcceptFormat(model.getFormat());

        LOG.debug(
                "Api request: TimeGrain: {}," +
                        " Table: {}," +
                        " Dimensions: {}," +
                        " Dimension Fields: {}," +
                        " Filters: {},\n" +
                        " Havings: {},\n" +
                        " Logical metrics: {},\n\n" +
                        " Sorts: {}," +
                        " Count: {}," +
                        " TopN: {}," +
                        " AsyncAfter: {}" +
                        " Format: {}" +
                        " Pagination: {}",
                granularity,
                table.getName(),
                dimensions,
                perDimensionFields,
                apiFilters,
                havings,
                logicalMetrics,
                sorts,
                count,
                topN,
                asyncAfterValue,
                format,
                paginationParameters
        );

        ApiRequestValidators.validateAggregatability(dimensions, apiFilters);
        ApiRequestValidators.validateTimeAlignment(granularity, intervals);

        return new DataApiRequestPojoImpl(
                format,
                paginationParameters,
                requestContext.getUriInfo(),
                table,
                granularity,
                dimensions,
                perDimensionFields,
                logicalMetrics,
                intervals,
                apiFilters,
                havings,
                having,
                sorts,
                count,
                topN,
                asyncAfterValue,
                timeZone,
                havingGenerator,
                dateTimeSort,
                druidFilterBuilder
        );
    }
}
