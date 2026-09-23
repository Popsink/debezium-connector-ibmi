# Purpose

Library to discover the journal for a schema and process the journal entries
Written to add ibmi support to debezium

# License

[Apache 2.0 license](https://www.apache.org/licenses/LICENSE-2.0) for consistency with debezium

# Downstream projects

debezium-connector-ibmi


# No journal entries found check journalling is enabled and set to *BOTH

`dspfd FINACC`

```
    File is currently journaled . . . . . . . . :            Yes
    Current or last journal . . . . . . . . . . :            FIGJRN
      Library . . . . . . . . . . . . . . . . . :            F63QULDVES
    Journal images  . . . . . . . . . . . . . . : IMAGES     *BOTH

```

# Permissions
```
GRTOBJAUT OBJ(<JRNLIB>) OBJTYPE(*LIB) USER(<CDC_USER>) AUT(*EXECUTE)
GRTOBJAUT OBJ(<JRNLIB>/*ALL) OBJTYPE(*JRNRCV) USER(<CDC_USER>) AUT(*USE)
GRTOBJAUT OBJ(<JRNLIB>/<JRN>) OBJTYPE(*JRN) USER(<CDC_USER>) AUT(*USE *OBJEXIST)

GRTOBJAUT OBJ(<FIGLIB>) OBJTYPE(*LIB) USER(<CDC_USER>) AUT(*EXECUTE)
GRTOBJAUT OBJ(<FIGLIB>/*ALL) OBJTYPE(*FILE) USER(<CDC_USER>) AUT(*USE)
```
Where:

* `<JRNLIB>` is the library where the journal and receivers reside
* `<JRN>` is the journal name
* `<FIGLIB>` is the Figaro database library
* `<CDC_USER>` is the username of the CDC service account

## Reference:

https://www.ibm.com/docs/en/i/7.4?topic=commands-journal

https://www.ibm.com/docs/en/i/7.4?topic=information-layouts-variable-length-portion-journal-entries#rzakivarlength__TBLOBJLVL

Will need at least the authorities described for RTVJRNE.



| Command For object | Referenced object For library | Authority needed or directory |
| ------------------ | ----------------------------- | ----------------------------- |
| RTVJRNE | Journal | \*USE | \*EXECUTE |
| RTVJRNE | Journal if FILE(*ALLFILE) is specified, no object selection is specified, the specified object has been deleted from the system, the specified object has never been journaled, \*IGNFILSLT or \*IGNOBJSLT is specified for any selected journal codes, or when OBJJID is specified, or the journal is a remote journal. | \*OBJEXIST, \*USE	\*EXECUTE |
| RTVJRNE | Journal receiver | \*USE \*EXECUTE |
| RTVJRNE | Nonintegrated file system object if specified | \*USE	\*EXECUTE |
| RTVJRNE | Integrated file system object if specified | \*R (It can be \*X as well if object is a directory and SUBTREE (\*ALL) is specified) | \*X |


# Debugging

use table query to investigate journal entries

see: https://www.ibm.com/docs/en/i/7.4?topic=services-display-journal-table-function and https://dawnmayi.com/2010/11/23/using-sql-to-interrogate-journal-objects/

```
select * from table (Display_Journal(
  'F63QULDVES',     'FIGJRN',  -- Journal library and name
  ' ','*CURCHAIN',        -- Receiver library and name
  CAST('2023-04-06-00.00.00.000000' as TIMESTAMP), -- Starting timestamp
  CAST(null as DECIMAL(21,0)), -- Starting sequence number
  '',              -- Journal codes
  '',              -- Journal entries
  '',  '',         -- Object library, Object name - library alone OK, name also needs library
  '*FILE', '*ALL', -- Object type, Object member
  '',              -- User
  '',              -- Job
  '',              -- Program
  ''       			-- EOF delay
) ) as x;
```

or simply:

```
SELECT entry_timestamp, receiver_name, sequence_number, journal_code, journal_entry_type, object
  FROM TABLE (QSYS2.DISPLAY_JOURNAL( 'F63QULDVES', 'FIGJRN')) AS JT
  ORDER BY entry_timestamp desc;
```

## list of journals

```
SELECT
	journal_receiver_name,
	first_sequence_number,
	LAST_sequence_number,
	attach_timestamp
FROM
	QSYS2.JOURNAL_RECEIVER_INFO
WHERE
	journal_name = 'FIGJRN'
	AND JOURNAL_RECEIVER_LIBRARY = 'F63QUALDB4'
	AND status = 'ONLINE'
ORDER BY
	attach_timestamp ASC ;
```

# TODO

Improved way of fetching the journal information

https://www.ibm.com/docs/en/i/7.4?topic=ssw_ibm_i_74/apis/qlirlibd.htm

That API is actually a program. You can still call it, but the mechanism is slightly different in JTOpen
The program name is QLIRLIBD
The library is likely QSYS, but *LIBL should work


# Limitations

Can only decode the journal data if the table structure is currently the same

[Unable to decode table structure changes](https://ibm-power-systems.ideas.ibm.com/ideas/IBMI-I-3211) as they are not documented D.CG or table creation D.CT
however we can detect what table changed

## Large objects

CLOB, DBCLOB, BLOB and XML columns all work the same way.

A journal entry never carries the data of a lob column. The record image holds a descriptor instead -
alignment padding, a byte of system information, the 4 byte length of the data, 8 reserved bytes and a
16 byte pointer to it - the data itself living in the journal receiver. The padding is whatever puts
the pointer on a 16 byte boundary, so the column occupies `padding + 29` bytes and how many depends on
where in the record it starts. That pointer
[cannot be passed to another job, nor stored to use later](https://www.ibm.com/docs/en/i/7.5?topic=entries-working-pointers-journal),
and can only be dereferenced by ILE code running in the job that called `QjoRetrieveJournalEntries`,
so it is of no use to us.

`AS400Lob` therefore decodes the descriptor only, which is what keeps the columns after a lob column
reading at the right offset, and the data itself is fetched by re-reading the entry with
`QSYS2.DISPLAY_JOURNAL` (`JournalLobFetcher`): that runs on the IBM i, in the job that owns the
pointers, so it resolves them and appends the lob data after the record image, one segment per lob
column separated by 16 `Q` bytes (`x'D8'`), a separator being written even for a column that is null
or empty. Verified against a live journal: for `(ID INT, HEAD CHAR(5), BODY CLOB(1M), TAIL CHAR(2))`
the record image is 50 bytes with `BODY` padded by 10 so its pointer lands on 32, and an update's
before image carries the value as it was, which re-reading the row could not give.

A blob's segment is used as it is; the others are decoded through the column's own CCSID, which for an
XML column is usually 1208 (UTF-8) rather than the EBCDIC of the rest of the record.

Three details of the layout are not what the documentation says, and each one cost a wrong decode until
a live entry showed it:

* the byte in front of the length is documented as `x'00'`, but a column holding double byte text
  carries `x'01'` there - it describes the data rather than being reserved, so it cannot be used to
  recognise a descriptor
* the length counts in whatever unit the column's length is declared in, so it needs scaling by
  `character_octet_length / length` to give bytes: a no-op for a CLOB or for XML (an XML column in CCSID
  1208 reported 41 for a 40 character document with one two byte character), 2 for the graphic and
  Unicode CCSIDs of a DBCLOB (11 characters for 22 bytes of data)
* the driver reports a DBCLOB whose CCSID is a Unicode one as **`NCLOB`**, not `DBCLOB`, so both names
  have to be recognised or the table fails with `Unsupported type`

## Pointer handles are counted, not deleted one at a time

An entry whose data is only reachable through a pointer - one for a file with a lob column, or one
whose entry specific data runs past 32766 bytes - also carries a pointer handle owning that
allocation. We never follow the pointer, but the allocation still has to be released: the API doc is
explicit that "if the handles are not deleted, the maximum number allowed can be reached, which will
prevent further retrieval of journal entries".

`QjoDeletePointerHandle` takes one handle per call, so returning them individually costs a round trip
per entry - 31 ms over a wide area link, 31 seconds for a thousand entry buffer, and paid even when
the lob data is never read. The same doc offers the bulk alternative: "the pointer handles will be
implicitly deleted when the process that requested the journal entries is ended". So `PointerHandles`
only counts them, and the connector replaces its connection once the budget is spent, so that the next
retrieve runs in a different host server job and the old one's handles go with it. That costs about
1.1 seconds including authentication - 0.02 ms an entry at the default threshold, against 31 ms an
entry for deleting them one by one.

Note it has to be the connection object, not just its service. QZRCSRVS are prestart jobs:
`disconnectService` returns one to a pool and the same object reconnects straight back into it with
the handles intact, where a new object lands on a different job with a fresh handle space. Nothing is
cancelled or ended on the system either way.

The budget resets when a retrieve reports a different job rather than when the disconnect is issued.
The job also ends for reasons the connector never initiates - the watchdog cancelling a hung retrieve,
a dropped connection reconnecting - and deriving the reset from the job identity covers all of them,
while leaving a disconnect that did not take effect visible as handles that keep accumulating.
`JournalLobIT` checks that recycling really does yield a different job: QZRCSRVS are prestart jobs, so
the same one coming back would mean nothing was freed.


When the library is pointing to the wrong journal, it can be fixed with:

```
STRJRNLIB LIB(<library>) JRN(<journal)
```

## Filtering on tables does not work properly across delete and create

Testing suggests that the filtering only applies to the table that exists during the call to fetch entries

# gotyas

From the documentation: https://www.ibm.com/docs/en/i/7.5?topic=ssw_ibm_i_75/apis/QJORJRNE.html

## Restrictions - at top of page
If the sequence number is reset in the range of the receivers specified, the first occurrence of starting sequence number or ending sequence number is used if these key fields are specified.

## Starting journal receiver name. - section "Receiver Range Format"
Note: For journal receivers with reset sequence numbers in the chain, the QjoRetrieveJournalEntries API may return the same journal entries for repeated API calls. To avoid receiving the same journal entries, change the starting journal receiver name field to indicate the next receiver in the chain after the initial call to the API.

So unless the start receiver is set we can end up in a loop, we can't simply use the `*CURCHAIN` to step over the journal entries

# seechange notes
a CR in development e.g. `CR 076737 / 37` has a schema `O#07673737` the shema isn't journaled so this won't work for CRs that haven't been promoted to holding or quality
A CR in holding/quality will use the normal schema for that environment
