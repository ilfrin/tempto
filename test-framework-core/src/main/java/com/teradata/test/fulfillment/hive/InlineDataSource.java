package com.teradata.test.fulfillment.hive;

import com.google.common.io.ByteSource;

import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;

import static com.google.common.collect.Iterators.cycle;
import static com.google.common.collect.Iterators.limit;
import static com.google.common.io.ByteSource.concat;
import static com.google.common.io.ByteSource.wrap;
import static com.google.common.io.Resources.asByteSource;
import static com.google.common.io.Resources.getResource;
import static java.lang.String.format;
import static java.util.Collections.singleton;

public abstract class InlineDataSource
        implements DataSource
{

    private final String tableName;
    private final String revisionMarker;

    private InlineDataSource(String tableName, String revisionMarker)
    {
        this.tableName = tableName;
        this.revisionMarker = revisionMarker;
    }

    public static DataSource createResourceDataSource(String tableName, String revisionMarker, String dataResource) {
        return new InlineDataSource(tableName, revisionMarker) {
            @Override
            public Collection<ByteSource> data()
            {
                return singleton(asByteSource(getResource(dataResource)));
            }
        };
    }

    public static DataSource createStringDataSource(String tableName, String revisionMarker, String data)
    {
        return new InlineDataSource(tableName, revisionMarker)
        {
            @Override
            public Collection<ByteSource> data()
            {
                return singleton(wrap(data.getBytes()));
            }
        };
    }

    public static DataSource createSameRowDataSource(String tableName, String revisionMarker, int splitCount, int rowsInEachSplit, String rowData)
    {
        return new InlineDataSource(tableName, revisionMarker)
        {
            @Override
            public Collection<ByteSource> data()
            {
                return new AbstractCollection<ByteSource>()
                {
                    @Override
                    public Iterator<ByteSource> iterator()
                    {
                        ByteSource singleRowSource = concat(wrap(rowData.getBytes()), wrap("\n".getBytes()));
                        ByteSource singleSplitSource = concat(limit(cycle(singleRowSource), rowsInEachSplit));
                        return limit(cycle(singleSplitSource), splitCount);
                    }

                    @Override
                    public int size()
                    {
                        return splitCount;
                    }
                };
            }
        };
    }

    @Override
    public String getPathSuffix()
    {
        // {TESTS_PATH}/datasets/{dataSetName}
        return format("inline-tables/%s", tableName);
    }

    @Override
    public String revisionMarker()
    {
        return revisionMarker;
    }
}