/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import java.util.ArrayList;

import com.ibm.as400.access.AS400DataType;
import com.ibm.as400.access.AS400Structure;
import com.ibm.as400.access.FieldDescription;

import io.debezium.ibmi.db2.journal.data.types.As400TextFactory;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.EntryHeader;

public class JournalRecordDecoder implements JournalEntryDeocder<JournalReceiver> {
    final AS400Structure structure;

    public JournalRecordDecoder(As400TextFactory textFactory) {
        FieldDescription[] fds = new FieldDescription[]{
                textFactory.charField(10, "start journal"),
                textFactory.charField(10, "start lib"),
                textFactory.charField(10, "end journal"),
                textFactory.charField(10, "end lib")
        };
        ArrayList<AS400DataType> dataTypes = new ArrayList<AS400DataType>();
        for (int i = 0; i < fds.length; i++) {
            dataTypes.add(fds[i].getDataType());
        }
        structure = new AS400Structure(dataTypes.toArray(new AS400DataType[dataTypes.size()]));
    }

    @Override
    public JournalReceiver decode(EntryHeader entryHeader, byte[] data, int offset) throws Exception {
        Object[] os = (Object[]) structure.toObject(data, offset + ENTRY_SPECIFIC_DATA_OFFSET + entryHeader.getEntrySpecificDataOffset());
        JournalReceiver start = new JournalReceiver((String) os[0], (String) os[1]);
        // JournalReceiver end = new JournalReceiver((String)os[2], (String)os[3]);
        return start;
    }

}
