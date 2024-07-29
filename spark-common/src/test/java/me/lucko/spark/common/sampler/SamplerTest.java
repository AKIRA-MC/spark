/*
 * This file is part of spark.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.lucko.spark.common.sampler;

import me.lucko.spark.common.sampler.async.AsyncSampler;
import me.lucko.spark.common.sampler.java.JavaSampler;
import me.lucko.spark.common.sampler.node.MergeMode;
import me.lucko.spark.common.sampler.source.ClassSourceLookup;
import me.lucko.spark.common.util.MethodDisambiguator;
import me.lucko.spark.proto.SparkSamplerProtos;
import me.lucko.spark.test.TestClass2;
import me.lucko.spark.test.plugin.TestCommandSender;
import me.lucko.spark.test.plugin.TestSparkPlugin;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class SamplerTest {

    @ParameterizedTest
    @EnumSource
    public void testSampler(ProfilerType profilerType, @TempDir Path directory) {
        if (profilerType == ProfilerType.ASYNC) {
            String os = System.getProperty("os.name").toLowerCase(Locale.ROOT).replace(" ", "");
            assumeTrue(os.equals("linux") || os.equals("macosx"), "async profiler is only supported on Linux and macOS");
        }

        Thread thread = new Thread(new TestClass2(), "Test Thread");
        thread.start();

        try (TestSparkPlugin plugin = new TestSparkPlugin(directory)) {
            Sampler sampler = new SamplerBuilder()
                    .threadDumper(new ThreadDumper.Specific(thread))
                    .threadGrouper(ThreadGrouper.BY_POOL)
                    .samplingInterval(10)
                    .forceJavaSampler(profilerType == ProfilerType.JAVA)
                    .completeAfter(2, TimeUnit.SECONDS)
                    .start(plugin.platform());

            assertInstanceOf(profilerType.expectedClass, sampler);

            assertNotEquals(-1, sampler.getAutoEndTime());
            sampler.getFuture().join();

            Sampler.ExportProps exportProps = new Sampler.ExportProps()
                    .creator(TestCommandSender.INSTANCE.toData())
                    .mergeMode(() -> MergeMode.sameMethod(new MethodDisambiguator(plugin.platform().createClassFinder())))
                    .classSourceLookup(() -> ClassSourceLookup.create(plugin.platform()));

            SparkSamplerProtos.SamplerData proto = sampler.toProto(plugin.platform(), exportProps);
            assertNotNull(proto);

            List<SparkSamplerProtos.ThreadNode> threads = proto.getThreadsList();
            assertEquals(1, threads.size());

            SparkSamplerProtos.ThreadNode protoThread = threads.get(0);
            assertEquals("Test Thread", protoThread.getName());
            assertTrue(protoThread.getChildrenList().stream().anyMatch(n -> n.getClassName().equals("me.lucko.spark.test.TestClass2") && n.getMethodName().equals("test")));
            assertTrue(protoThread.getChildrenList().stream().anyMatch(n -> n.getClassName().equals("me.lucko.spark.test.TestClass2") && n.getMethodName().equals("testA")));
            assertTrue(protoThread.getChildrenList().stream().anyMatch(n -> n.getClassName().equals("me.lucko.spark.test.TestClass2") && n.getMethodName().equals("testB")));
        }
    }

    public enum ProfilerType {
        JAVA(JavaSampler.class),
        ASYNC(AsyncSampler.class);

        private final Class<? extends Sampler> expectedClass;

        ProfilerType(Class<? extends Sampler> expectedClass) {
            this.expectedClass = expectedClass;
        }
    }

}
