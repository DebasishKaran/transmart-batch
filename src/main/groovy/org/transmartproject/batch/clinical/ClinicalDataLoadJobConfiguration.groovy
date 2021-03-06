package org.transmartproject.batch.clinical

import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.configuration.annotation.JobScope
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.job.builder.FlowBuilder
import org.springframework.batch.core.job.flow.Flow
import org.springframework.batch.core.job.flow.FlowJob
import org.springframework.batch.core.job.flow.support.SimpleFlow
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.item.ItemProcessor
import org.springframework.batch.item.ItemWriter
import org.springframework.batch.item.file.FlatFileItemReader
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.io.Resource
import org.transmartproject.batch.beans.AbstractJobConfiguration
import org.transmartproject.batch.beans.JobScopeInterfaced
import org.transmartproject.batch.beans.StepScopeInterfaced
import org.transmartproject.batch.clinical.facts.*
import org.transmartproject.batch.clinical.patient.InsertPatientTrialTasklet
import org.transmartproject.batch.clinical.patient.InsertUpdatePatientDimensionTasklet
import org.transmartproject.batch.clinical.variable.ReadVariablesTasklet
import org.transmartproject.batch.clinical.xtrial.GatherXtrialNodesTasklet
import org.transmartproject.batch.clinical.xtrial.XtrialMapping
import org.transmartproject.batch.clinical.xtrial.XtrialMappingWriter
import org.transmartproject.batch.concept.ConceptPath
import org.transmartproject.batch.concept.InsertConceptCountsTasklet
import org.transmartproject.batch.db.DatabaseImplementationClassPicker
import org.transmartproject.batch.facts.ClinicalFactsRowSet
import org.transmartproject.batch.facts.DeleteObservationFactTasklet
import org.transmartproject.batch.facts.ObservationFactTableWriter
import org.transmartproject.batch.support.JobParameterFileResource
import org.transmartproject.batch.tag.TagsLoadJobConfiguration
import org.transmartproject.batch.concept.DeleteConceptCountsTasklet
import org.transmartproject.batch.concept.oracle.OracleInsertConceptCountsTasklet
import org.transmartproject.batch.concept.postgresql.PostgresInsertConceptCountsTasklet

/**
 * Spring configuration for the clinical data job.
 */
@Configuration
@ComponentScan(['org.transmartproject.batch.clinical',
                'org.transmartproject.batch.concept',
                'org.transmartproject.batch.patient'])
@Import(TagsLoadJobConfiguration)
class ClinicalDataLoadJobConfiguration extends AbstractJobConfiguration {

    public static final String JOB_NAME = 'ClinicalDataLoadJob'
    public static final int CHUNK_SIZE = 10000

    @javax.annotation.Resource(name='TagsLoadJob')
    Job tagsLoadJob

    @javax.annotation.Resource
    Tasklet insertConceptsTasklet

    @javax.annotation.Resource
    Tasklet gatherCurrentConceptsTasklet

    @javax.annotation.Resource
    Tasklet gatherCurrentPatientsTasklet

    @Bean(name = 'ClinicalDataLoadJob')
    @Override
    Job job() {
        FlowJob job =
            jobs.get(JOB_NAME)
                    .start(mainFlow())
                    .end()
                    .build()
        job.jobParametersIncrementer = jobParametersIncrementer
        job
    }

    @Bean
    Flow mainFlow() {
        new FlowBuilder<SimpleFlow>('mainFlow')
                .start(readControlFilesFlow()) //reads control files (column map, word map, xtrial)

                // read stuff from the DB
                .next(allowStartStepOf(this.&getGatherCurrentPatientsTasklet))
                .next(allowStartStepOf(this.&getGatherCurrentConceptsTasklet))
                .next(allowStartStepOf(this.&gatherXtrialNodesTasklet))

                // delete data we'll be replacing
                .next(stepOf(this.&deleteObservationFactTasklet))
                .next(stepOf(this.&deleteConceptCountsTasklet))

                // main data reading and insertion step (in observation_fact)
                .next(rowProcessingStep())
                .next(tagsLoadJobStep())

                // insertion of ancillary data
                .next(stepOf(this.&insertUpdatePatientDimensionTasklet)) //insert/update patient_dimension
                .next(stepOf(this.&insertPatientTrialTasklet))           //insert patient_trial rows
                .next(stepOf(this.&getInsertConceptsTasklet))            //insert i2b2, i2b2_secure, concept_dimension
                .next(stepOf('insertConceptCounts',
                             insertConceptCountsTasklet(null, null)))    //insert concept_counts rows
                .build()
    }

    @Bean
    Flow readControlFilesFlow() {
        def readVariablesTasklet = steps.get('readVariablesTasklet')
                .allowStartIfComplete(true)
                .tasklet(readVariablesTasklet())
                .build()

        def readWordMapTasklet = steps.get('readWordMapTasklet')
                .allowStartIfComplete(true)
                .tasklet(readWordMapTasklet())
                .build()

        def readXtrialsTasklet = steps.get('readXtrialsFileTasklet')
                .allowStartIfComplete(true)
                .chunk(5)
                .reader(xtrialMappingReader())
                .writer(xtrialsFileTaskletWriter())
                .build()

        parallelFlowOf(
                'readControlFilesFlow',
                readVariablesTasklet,
                readWordMapTasklet,
                readXtrialsTasklet,
        )
    }

    @Bean
    @JobScope
    FlatFileItemReader<XtrialMapping> xtrialMappingReader() {
        tsvFileReader(
                xtrialFileResource(),
                beanClass: XtrialMapping,
                columnNames: ['study_prefix', 'xtrial_prefix'],
                linesToSkip: 1,
                strict: false,
        )
    }

    @Bean
    @JobScope
    XtrialMappingWriter xtrialsFileTaskletWriter() {
        new XtrialMappingWriter()
    }

    @Bean
    @JobScopeInterfaced
    Resource xtrialFileResource() {
        new JobParameterFileResource(parameter: ClinicalExternalJobParameters.XTRIAL_FILE)
    }

    @Bean
    Step rowProcessingStep() {
        steps.get('rowProcessingStep')
                .chunk(CHUNK_SIZE)
                .reader(dataRowReader()) //read data
                .processor(compositeOf(
                    wordReplaceProcessor(), //replace words, if such is configured
                    rowToFactRowSetConverter(), //converts each Row to a FactRowSet
                ))
                .writer(factRowSetTableWriter()) //writes the FactRowSets in lt_src_clinical_data
                .listener(progressWriteListener())
                .build()
    }

    @Bean
    Step tagsLoadJobStep() {
        steps.get('tagsLoadJobStep')
                .job(tagsLoadJob)
                .build()
    }

    @Bean
    @JobScopeInterfaced
    Tasklet readWordMapTasklet() {
        new ReadWordMapTasklet()
    }

    @Bean
    @JobScopeInterfaced
    Tasklet readVariablesTasklet() {
        new ReadVariablesTasklet()
    }

    @Bean
    @JobScopeInterfaced
    Tasklet gatherXtrialNodesTasklet() {
        new GatherXtrialNodesTasklet()
    }

    @Bean
    @StepScope
    ClinicalDataRowReader dataRowReader() {
        new ClinicalDataRowReader()
    }

    @Bean
    @StepScopeInterfaced
    ItemProcessor<ClinicalDataRow,ClinicalDataRow> wordReplaceProcessor() {
        new WordReplaceItemProcessor()
    }

    @Bean
    @StepScopeInterfaced
    ItemProcessor<ClinicalDataRow, ClinicalFactsRowSet> rowToFactRowSetConverter() {
        new ClinicalDataRowProcessor()
    }

    @Bean
    ItemWriter<ClinicalFactsRowSet> factRowSetTableWriter() {
        new ObservationFactTableWriter()
    }

    @Bean
    @JobScopeInterfaced
    Tasklet insertUpdatePatientDimensionTasklet() {
        new InsertUpdatePatientDimensionTasklet()
    }

    @Bean
    @JobScopeInterfaced
    Tasklet insertPatientTrialTasklet() {
        new InsertPatientTrialTasklet()
    }

    @Bean
    @JobScopeInterfaced
    Tasklet insertConceptCountsTasklet(DatabaseImplementationClassPicker picker,
                                       @Value("#{jobParameters['TOP_NODE']}") ConceptPath topNode) {
        picker.instantiateCorrectClass(
                OracleInsertConceptCountsTasklet,
                PostgresInsertConceptCountsTasklet)
        .with { InsertConceptCountsTasklet t ->
            t.basePath = topNode
            t
        }
    }

    @Bean
    @JobScopeInterfaced
    Tasklet deleteObservationFactTasklet() {
        new DeleteObservationFactTasklet()
    }

    @Bean
    @JobScopeInterfaced
    Tasklet deleteConceptCountsTasklet(
            @Value("#{jobParameters['TOP_NODE']}") topNode) {
        new DeleteConceptCountsTasklet(basePath: new ConceptPath(topNode))
    }

    @Bean
    static BeanFactoryPostProcessor makeTagLoadNonStrict() {
        // tags are optional when we're not running the tag job
        { ConfigurableListableBeanFactory beanFactory ->
            beanFactory.getBeanDefinition('scopedTarget.tagReader')
                    .propertyValues
                    .addPropertyValue('strict', false)
        } as BeanFactoryPostProcessor
    }
}
