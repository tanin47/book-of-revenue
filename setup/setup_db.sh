#!/usr/bin/env sh

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

psql -d postgres -a -f ${DIR}/db.sql
