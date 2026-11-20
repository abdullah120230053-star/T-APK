import hmac, hashlib

id1 = '00000000-0000-0000-0000-000000000001'
id2 = '00000000-0000-0000-0000-000000000002'
n1 = '01' * 16
n2 = '02' * 16

for ids in [(id1, id2), (id2, id1)]:
    for ns in [(n1, n2), (n2, n1)]:
        for trailing in ['', '\n']:
            m = '\n'.join(['tahany-sas-v1', ids[0], ids[1], ns[0], ns[1]]) + trailing
            for key, data in [
                (b'tahany-sas-v1', m.encode()),
                (m.encode(), b'tahany-sas-v1'),
            ]:
                d = hmac.new(key, data, hashlib.sha256).digest()
                v = int.from_bytes(d[:8], 'big') % 1000000
                print(v, ids[0][-1], ns[0][:2], repr(trailing), key[:14])
                if v == 913888:
                    print('MATCH')
