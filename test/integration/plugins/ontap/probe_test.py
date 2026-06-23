
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#   http://www.apache.org/licenses/LICENSE-2.0
import json
from marvin.cloudstackAPI import listTemplates, listServiceOfferings, listNetworks
from marvin.cloudstackTestCase import cloudstackTestCase

class ProbeResources(cloudstackTestCase):
    @classmethod
    def setUpClass(cls):
        tc = super(ProbeResources, cls).getClsTestClient()
        cls.api = tc.getApiClient()

    def test_01_probe(self):
        out = {}
        cmd = listTemplates.listTemplatesCmd()
        cmd.templatefilter = "executable"
        resp = self.api.listTemplates(cmd)
        out["templates"] = [{"id": t.id, "name": t.name, "hypervisor": getattr(t,"hypervisor","?"), "status": getattr(t,"status","?")} for t in (resp or [])]
        cmd2 = listServiceOfferings.listServiceOfferingsCmd()
        resp2 = self.api.listServiceOfferings(cmd2)
        out["offerings"] = [{"id": s.id, "name": s.name, "cpu": getattr(s,"cpunumber","?"), "mem": getattr(s,"memory","?")} for s in (resp2 or [])]
        cmd3 = listNetworks.listNetworksCmd()
        cmd3.listall = True
        resp3 = self.api.listNetworks(cmd3)
        out["networks"] = [{"id": n.id, "name": n.name, "type": getattr(n,"type","?"), "state": getattr(n,"state","?")} for n in (resp3 or [])]
        with open("/tmp/cs_probe_out.json","w") as f:
            json.dump(out, f, indent=2)
        self.assertTrue(True)
