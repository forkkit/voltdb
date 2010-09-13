/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.voltdb.twitter.hadoop.mr;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.Tool;

public class HashtagSort extends Configured implements Tool {

    private static final Log LOG = LogFactory.getLog(HashtagSort.class);

    public static class Map extends Mapper<LongWritable, Text, IntWritable, Text> {

        private Pattern regex;
        private Matcher matcher;
        private IntWritable count;
        private Text hashtag;

        public Map() {
            regex = Pattern.compile("^(.*)\t(\\d+)$");
            count = new IntWritable();
            hashtag = new Text();
        }

        @Override
        public void map(LongWritable offset, Text line, Context context) throws IOException, InterruptedException {
            matcher = regex.matcher(line.toString());
            if (matcher.matches()) {
                count.set(-Integer.parseInt(matcher.group(2)));
                hashtag.set(matcher.group(1));
                context.write(count, hashtag);
            } else {
                LOG.warn("Error parsing line: " + line);
            }
        }

    }

    public static class Reduce extends Reducer<IntWritable, Text, Text, IntWritable> {

        @Override
        public void reduce(IntWritable count, Iterable<Text> hashtags, Context context) throws IOException, InterruptedException {
            for (Text hashtag : hashtags) {
                count.set(-count.get());
                context.write(hashtag, count);
            }
        }

    }

    private String inputDir;
    private String outputDir;

    public HashtagSort(String inputDir, String outputDir) {
        this.inputDir = inputDir;
        this.outputDir = outputDir;
    }

    @Override
    public int run(String[] args) throws Exception {
        Job job = new Job(getConf(), "hashtag-sort");
        job.setJarByClass(HashtagSort.class);
        job.setMapperClass(Map.class);
        job.setReducerClass(Reduce.class);

        // input/output paths
        FileInputFormat.addInputPaths(job, inputDir);
        FileOutputFormat.setOutputPath(job, new Path(outputDir));

        // input/output formats
        job.setInputFormatClass(TextInputFormat.class);
        job.setOutputFormatClass(TextOutputFormat.class);

        // map output classes
        job.setMapOutputKeyClass(IntWritable.class);
        job.setMapOutputValueClass(Text.class);

        // reduce output classes
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(IntWritable.class);

        return job.waitForCompletion(true) ? 0 : 1;
    }

}
