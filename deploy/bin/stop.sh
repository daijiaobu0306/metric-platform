#!/bin/bash
LOG_DIR="../backend/logs"
if [ -f $LOG_DIR/app.pid ]; then
  kill $(cat $LOG_DIR/app.pid) && echo "Stopped" && rm $LOG_DIR/app.pid
else
  echo "Not running"
fi