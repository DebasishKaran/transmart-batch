package org.transmartproject.batch.reader

import org.springframework.batch.item.file.FlatFileItemReader
import org.springframework.batch.item.file.LineMapper
import org.springframework.batch.item.file.MultiResourceItemReader
import org.springframework.core.io.Resource

import javax.annotation.PostConstruct

/**
 * Reader of Row that is prepared for multiple input Resources and implements LineMapper.<br>
 * It uses internally a FlatFileItemReader which skips the header.<br>
 * Should be subclassed by implementing addResources and mapLine
 */
abstract class GenericRowReader<Row> extends MultiResourceItemReader<Row> implements LineMapper<Row> {

    @PostConstruct
    void init() {
        List<Resource> list = getResourcesToProcess()
        setResources(list as Resource[]) //sets the resources to read
        FlatFileItemReader<Row> reader = new FlatFileItemReader<>()
        reader.setLineMapper(this)
        reader.setLinesToSkip(1) //ignores the header
        setDelegate(reader)
        setStrict(true) //we strictly must have data
    }

    /**
     * @return List of Resources to be processed
     */
    abstract List<Resource> getResourcesToProcess()

}