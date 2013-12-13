/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.elasticsearch.hadoop.cascading;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.RecordReader;
import org.elasticsearch.hadoop.cfg.InternalConfigurationOptions;
import org.elasticsearch.hadoop.cfg.Settings;
import org.elasticsearch.hadoop.cfg.SettingsManager;
import org.elasticsearch.hadoop.mr.ESInputFormat;
import org.elasticsearch.hadoop.mr.ESOutputFormat;
import org.elasticsearch.hadoop.mr.HadoopCfgUtils;
import org.elasticsearch.hadoop.serialization.JdkValueReader;
import org.elasticsearch.hadoop.serialization.SerializationUtils;
import org.elasticsearch.hadoop.util.StringUtils;

import cascading.flow.FlowProcess;
import cascading.scheme.Scheme;
import cascading.scheme.SinkCall;
import cascading.scheme.SourceCall;
import cascading.tap.Tap;
import cascading.tuple.Fields;
import cascading.tuple.Tuple;
import cascading.tuple.TupleEntry;

/**
 * Cascading Scheme handling
 */
@SuppressWarnings("rawtypes")
class ESHadoopScheme extends Scheme<JobConf, RecordReader, OutputCollector, Object[], Object[]> {

    private final String index;
    private final String host;
    private final int port;

    ESHadoopScheme(String host, int port, String index, Fields fields) {
        this.index = index;
        this.host = host;
        this.port = port;
        if (fields != null) {
            setSinkFields(fields);
            setSourceFields(fields);
        }
    }

    @Override
    public void sourcePrepare(FlowProcess<JobConf> flowProcess, SourceCall<Object[], RecordReader> sourceCall) throws IOException {
        super.sourcePrepare(flowProcess, sourceCall);

        Object[] context = new Object[2];
        context[0] = sourceCall.getInput().createKey();
        context[1] = sourceCall.getInput().createValue();

        sourceCall.setContext(context);
    }

    @Override
    public void sourceCleanup(FlowProcess<JobConf> flowProcess, SourceCall<Object[], RecordReader> sourceCall) throws IOException {
        sourceCall.setContext(null);
    }

    @Override
    public void sinkPrepare(FlowProcess<JobConf> flowProcess, SinkCall<Object[], OutputCollector> sinkCall) throws IOException {
        super.sinkPrepare(flowProcess, sinkCall);

        Fields sinkCallFields = sinkCall.getOutgoingEntry().getFields();
        Fields sinkFields = (sinkCallFields.isDefined() ? sinkCallFields : getSinkFields());
        List<String> tupleNames = resolveNames(sinkFields);

        Object[] context = new Object[1];
        context[0] = tupleNames;
        sinkCall.setContext(context);
    }

    public void sinkCleanup(FlowProcess<JobConf> flowProcess, SinkCall<Object[], OutputCollector> sinkCall) throws IOException {
        sinkCall.setContext(null);
    }

    private List<String> resolveNames(Fields fields) {

        //TODO: add handling of undefined types (Fields.UNKNOWN/ALL/RESULTS...)
        if (fields == null || !fields.isDefined()) {
            // use auto-generated name
            return Collections.emptyList();
        }

        int size = fields.size();
        List<String> names = new ArrayList<String>(size);
        for (int fieldIndex = 0; fieldIndex < size; fieldIndex++) {
            names.add(fields.get(fieldIndex).toString());
        }

        return names;
    }

    @Override
    public void sourceConfInit(FlowProcess<JobConf> flowProcess, Tap<JobConf, RecordReader, OutputCollector> tap, JobConf conf) {
        initTargetUri(conf);
        conf.setInputFormat(ESInputFormat.class);
        conf.set(InternalConfigurationOptions.INTERNAL_ES_TARGET_FIELDS,
                StringUtils.concatenate(resolveNames(getSourceFields()), ","));
    }

    @Override
    public void sinkConfInit(FlowProcess<JobConf> flowProcess, Tap<JobConf, RecordReader, OutputCollector> tap, JobConf conf) {
        initTargetUri(conf);
        conf.setOutputFormat(ESOutputFormat.class);
        // define an output dir to prevent Cascading from setting up a TempHfs and overriding the OutputFormat
        Settings set = SettingsManager.loadFrom(conf);

        SerializationUtils.setValueWriterIfNotSet(set, CascadingValueWriter.class, LogFactory.getLog(ESTap.class));
        SerializationUtils.setValueReaderIfNotSet(set, JdkValueReader.class, LogFactory.getLog(ESTap.class));

        // NB: we need to set this property even though it is not being used - and since and URI causes problem, use only the resource/file
        //conf.set("mapred.output.dir", set.getTargetUri() + "/" + set.getTargetResource());
        HadoopCfgUtils.setFileOutputFormatDir(conf, set.getTargetResource());
        HadoopCfgUtils.setOutputCommitterClass(conf, ESOutputFormat.ESOldAPIOutputCommitter.class.getName());

    }

    private void initTargetUri(JobConf conf) {
        // init
        SettingsManager.loadFrom(conf).setHosts(host).setPort(port).setResource(index).save();
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean source(FlowProcess<JobConf> flowProcess, SourceCall<Object[], RecordReader> sourceCall) throws IOException {
        Object[] context = sourceCall.getContext();

        if (!sourceCall.getInput().next(context[0], context[1])) {
            return false;
        }

        TupleEntry entry = sourceCall.getIncomingEntry();

        Map data = (Map) context[1];

        if (entry.getFields().isDefined()) {
            // lookup using writables
            Text lookupKey = new Text();
            // TODO: it's worth benchmarking whether using an index/offset yields significantly better performance
            for (Comparable field : entry.getFields()) {
                //NB: coercion should be applied automatically by the TupleEntry
                lookupKey.set(field.toString());
                entry.setObject(field, data.get(lookupKey));
            }
        }
        else {
            Tuple.elements(entry.getTuple()).addAll(data.values());
        }

        return true;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void sink(FlowProcess<JobConf> flowProcess, SinkCall<Object[], OutputCollector> sinkCall) throws IOException {
        sinkCall.getOutput().collect(null, sinkCall);
    }
}