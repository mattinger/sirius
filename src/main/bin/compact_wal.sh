#!/bin/bash

UBERSTORE_BASE=`dirname $0`
UBERSTORE_BASE=`cd $UBERSTORE_BASE/../..; pwd`

WAL_DIR=$UBERSTORE_BASE/uberstore
BACKUP_WAL_DIR=$UBERSTORE_BASE/uberstore-backup
COMPACTED_WAL_DIR=$UBERSTORE_BASE/uberstore-compacted

echo "Removing any existing compacted logs from $COMPACTED_WAL_DIR"
rm -rf $COMPACTED_WAL_DIR

echo "Removing any existing backup logs from $BACKUP_WAL_DIR"
rm -rf $BACKUP_WAL_DIR

echo "Copying $WAL_DIR to $BACKUP_WAL_DIR"
cp -r $WAL_DIR $BACKUP_WAL_DIR

echo "Compacting $WAL_DIR into $COMPACTED_WAL_DIR"
$UBERSTORE_BASE/sirius-standalone/bin/waltool compact two-pass $WAL_DIR $COMPACTED_WAL_DIR

echo "Copying $COMPACTED_WAL_DIR to $WAL_DIR"
for f in `ls -1 $COMPACTED_WAL_DIR`
do
  echo "  Copying $f"
  # This is being done with cat to preserve ownership and permissions.
  cat $COMPACTED_WAL_DIR/$f > $WAL_DIR/$f
done

echo "Compaction Completed"
