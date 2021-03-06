package org.transmartproject.batch.startup

import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.converter.JobParametersConverter
import org.springframework.batch.core.launch.support.CommandLineJobRunner
import org.springframework.batch.core.launch.support.SystemExiter
import org.transmartproject.batch.clinical.ClinicalExternalJobParameters
import org.transmartproject.batch.highdim.mrna.data.MrnaDataExternalJobParameters
import org.transmartproject.batch.highdim.mrna.platform.MrnaAnnotationExternalJobParameters
import org.transmartproject.batch.tag.TagsLoadJobParameters


import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Entry point for the application.
 */
@SuppressWarnings(['SystemExit', 'SystemErrPrint'])
final class RunJob {

    public static final String DEFAULT_BATCHDB_PROPERTIES_LOCATION = 'file:./batchdb.properties'

    private final Map<String, Class<? extends ExternalJobParameters>> parametersTypeMap = [
            'clinical': ClinicalExternalJobParameters,
            'annotation': MrnaAnnotationExternalJobParameters,
            'tags': TagsLoadJobParameters,
            'expression': MrnaDataExternalJobParameters,
    ]

    OptionAccessor opts

    JobParameters finalJobParameters

    private final static String USAGE = '''transmart-batch-capsule.jar -p <params file>
    [ -d <param=value> | [ -d <param2=value2> | ... ]]
    [ -c <file> ]
    (
        ((-r | -s | -a ) -j <job id>)) |
        [-n])'''

    private static CliBuilder createCliBuilder() {
        def cli = new CliBuilder(usage: USAGE)
        cli.writer = new PrintWriter(System.err)
        cli.with {
            d args: 2, valueSeparator: '=', argName: 'param=value', 'override/supplement params file parameter'
            p args: 1, argName: 'file location', 'specify params file', required: true
            c longOpt: 'config', args: 1, 'location of database configuration properties file ' +
                    "(default: $DEFAULT_BATCHDB_PROPERTIES_LOCATION)"
            j longOpt: 'jobIdentifier', args: 1, "the id or name of a job instance"
            r longOpt: 'restart', 'restart the last failed execution'
            s longOpt: 'stop', 'stop a running execution'
            a longOpt: 'abandon', 'abandon a stopped execution'
            n longOpt: 'next', 'start the next in a sequence according to the JobParametersIncrementer'
            it
        }
    }

    static void main(String... args) {
        CommandLineJobRunner.presetSystemExiter(new OnErrorSystemExiter())
        def runJob = createInstance(args)
        runJob.run()
    }

    static RunJob createInstance(String... args) {
        def cliBuilder = createCliBuilder()
        OptionAccessor opts = cliBuilder.parse(args)
        if (!opts) {
            System.exit 1
        }
        new RunJob(opts: opts)
    }

    int run() {
        def propSource = batchPropertiesPath
        if (!propSource) {
            System.exit 1
        }
        System.setProperty 'propertySource', propSource

        def externalJobParams = externalJobParameters
        if (!externalJobParams) {
            System.exit 1
        }

        def runner = new CommandLineJobRunner()
        finalJobParameters = externalJobParams as JobParameters
        runner.jobParametersConverter = new JobParametersConverter() {
            @SuppressWarnings('UnusedMethodParameter')
            JobParameters getJobParameters(Properties properties) {
                finalJobParameters
            }
            @SuppressWarnings('UnusedMethodParameter')
            Properties getProperties(JobParameters params) {
                externalJobParams as Properties
            }
        }

        String jobIdentifier = calculateJobIdentifier(externalJobParameters.jobPath)
        if (!jobIdentifier) {
            System.exit 1
        }
        runner.start(externalJobParameters.jobPath.name,
                jobIdentifier,
                [] as String[] /* converter above takes care of params */,
                (
                        [] +
                                (opts.r ? '-restart' : []) +
                                (opts.s ? '-stop' : []) +
                                (opts.a ? '-abandon' : []) +
                                (opts.n ? '-next' : [])) as Set)
    }

    String getBatchPropertiesPath() {
        if (!opts.c) {
            DEFAULT_BATCHDB_PROPERTIES_LOCATION
        } else {
            Path path = Paths.get((String) opts.c)
            if (!Files.isReadable(path) || !Files.isRegularFile(path)) {
                System.err.println "'$path' is not a readable file"
                return null
            }
            "file:${path.toAbsolutePath()}"
        }
    }

    Path getParamsFilePath() {
        Path path = Paths.get((String) opts.p)
        if (!Files.isReadable(path) || !Files.isRegularFile(path)) {
            System.err.println "'$path' is not a readable file"
            return null
        }
        path
    }

    String calculateJobIdentifier(Class configurationClass) {
        /* CommandLineJobRunner uses -j both for a bean job name
         * (or logical job name to be found by a JobLocator) or
         * a JobInstance identifier or name.
         *
         * The job name should also match its bean name because
         * on restarts the job name saved in the registry is
         * used as the bean name
         */
         if (opts.r || opts.s || opts.a) {
             if (!opts.j) {
                 System.err.println('The -j parameter must be specified ' +
                         'when the options -n, -s or -a are used')
                 null
             } else {
                 opts.j
             }
         } else {
             def job = configurationClass.JOB_NAME
             if (!job) {
                 throw new IllegalStateException("Class $configurationClass should " +
                         "have a static property 'JOB_NAME'")
             }

             job
         }
    }

    ExternalJobParameters getExternalJobParameters() {
        def ds = opts.ds
        def paramOverrides = [:]
        if (ds) {
            for (int i = 0; i < ds.size() / 2; i++) {
                paramOverrides[ds[i]] = ds[i + 1]
            }
        }

        def paramsFile = paramsFilePath
        if (!paramsFile) {
            return null
        }

        try {
            ExternalJobParameters.fromFile(parametersTypeMap, paramsFile, paramOverrides)
        } catch (InvalidParametersFileException e) {
            System.err.println "Invalid parameters file: ${e.message}"
            null
        }
    }

    static class OnErrorSystemExiter implements SystemExiter {
        @Override
        void exit(int status) {
            if (status != 0) {
                System.exit(status)
            }
        }
    }

}
