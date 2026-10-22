<!--
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
-->
# ONTAP scale benchmark

Scale matrix: [ONTAP Plugin - CloudStack Operations, Scale & Parallel Test Matrix](https://netapp.atlassian.net/wiki/spaces/OSSG/pages/608854350).

Test ids written to the CSVs and report map to the page sections as follows:

| Test id | Page section | What it measures |
| --- | --- | --- |
| `pool_sequential_create` | 5.1.1 | Sequential create (1 pool at a time, cumulative to N) |
| `pool_sequential_delete` | 5.1.2 | Sequential delete (1 pool at a time, from N remaining) |
| `pool_concurrent_create` | 6.1.1 | Parallel pool creation |
| `pool_concurrent_delete` | 6.1.2 | Parallel pool deletion |
| `vm_sequential_create` | 5.2.1 | Sequential create (1 VM at a time, cumulative to N) |
| `vm_sequential_delete` | 5.2.2 | Sequential delete (1 VM at a time, from N remaining) |
| `vm_concurrent_create` | 6.2.1 | Parallel VM creation |
| `vm_concurrent_delete` | 6.2.2 | Parallel VM deletion |
| Report "Results log" table | 9 | Results log |

Keep section numbers and the page link out of the code. How to run each script is in `README.md` and `USAGE.md`.
- Protocols, checkpoints, and concurrency levels live in `config.yaml`. `run.py` is the entry point. Do not add a CLI flag for a field that already exists in config.
- Use `benchmark_support` for run ids, resource names, `mean_seconds`, and logging. Sequential and concurrency scripts must use the same helpers and the same log format. Do not hand-roll a second average or a second logger.
- Run ids are letters, digits, and underscores. Storage pool names disallow `-`. VM hostnames disallow `_`; `format_vm_name` / `vm_name_token` are the only rewrite, and cleanup must use them so created VMs are the ones deleted.
- Cleanup must stay inside the configured name prefix, zone, cluster (pools), and storage provider. Do not delete a resource just because its name contains a short substring.
