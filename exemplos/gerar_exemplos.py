#!/usr/bin/env python3
"""Gera dois arquivos EFD ICMS/IPI 100% fictícios para testar o validador.

  efd-exemplo-valido.txt  janeiro/2025, uma NF-e de saída com ICMS: deve passar.
  efd-exemplo-com-erro.txt  o mesmo arquivo com o ICMS do C190 diferente do C100.

CNPJ, IE, CPF e chave de NF-e são inventados, mas com dígitos verificadores
válidos (o PVA confere isso). Uso: python3 gerar_exemplos.py
"""
from pathlib import Path


def dv_cnpj(base12):
    def dv(nums, pesos):
        r = sum(int(n) * p for n, p in zip(nums, pesos)) % 11
        return '0' if r < 2 else str(11 - r)
    d1 = dv(base12, [5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2])
    d2 = dv(base12 + d1, [6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2])
    return base12 + d1 + d2


def dv_cpf(base9):
    def dv(nums):
        r = sum(int(n) * p for n, p in zip(nums, range(len(nums) + 1, 1, -1))) % 11
        return '0' if r < 2 else str(11 - r)
    d1 = dv(base9)
    return base9 + d1 + dv(base9 + d1)


def dv_ie_ce(base8):
    r = sum(int(n) * p for n, p in zip(base8, range(9, 1, -1))) % 11
    d = 11 - r
    return base8 + ('0' if d >= 10 else str(d))


def dv_chave(base43):
    pesos = [2, 3, 4, 5, 6, 7, 8, 9]
    s = sum(int(n) * pesos[i % 8] for i, n in enumerate(reversed(base43)))
    d = 11 - s % 11
    return base43 + ('0' if d >= 10 else str(d))


CNPJ = dv_cnpj('112223330001')        # empresa fictícia
IE = dv_ie_ce('06000001')
CNPJ_CLIENTE = dv_cnpj('445556660001')  # cliente fictício
IE_CLIENTE = dv_ie_ce('06000002')
CPF_CONTADOR = dv_cpf('123456789')
FORTALEZA = '2304400'
CHAVE = dv_chave('23' + '2501' + CNPJ + '55' + '001' + '000000123' + '1' + '12345678')


def montar(icms_c190='180,00'):
    corpo = {
        '0': [
            ['0000', '019', '0', '01012025', '31012025', 'EMPRESA FICTICIA DE EXEMPLO LTDA', CNPJ, '', 'CE', IE,
             FORTALEZA, '', '', 'A', '1'],
            ['0001', '0'],
            ['0005', 'EXEMPLO', '60000000', 'RUA DE EXEMPLO', '100', '', 'CENTRO', '8500000000', '', 'exemplo@example.com'],
            ['0100', 'CONTADOR FICTICIO', CPF_CONTADOR, 'CE000000O0', '', '60000000', 'RUA DE EXEMPLO', '200', '',
             'CENTRO', '8500000000', '', 'contador@example.com', FORTALEZA],
            ['0150', 'C1', 'CLIENTE FICTICIO LTDA', '01058', CNPJ_CLIENTE, '', IE_CLIENTE, FORTALEZA, '',
             'AVENIDA DE EXEMPLO', '300', '', 'CENTRO'],
        ],
        'B': [['B001', '1']],
        'C': [
            ['C001', '0'],
            ['C100', '1', '0', 'C1', '55', '00', '001', '123', CHAVE, '15012025', '15012025', '1000,00', '0', '0,00',
             '0,00', '1000,00', '9', '0,00', '0,00', '0,00', '1000,00', '180,00', '0,00', '0,00', '0,00', '', '', '',
             ''],
            ['C190', '000', '5102', '18,00', '1000,00', '1000,00', icms_c190, '0,00', '0,00', '0,00', '0,00', ''],
        ],
        'D': [['D001', '1']],
        'E': [
            ['E001', '0'],
            ['E100', '01012025', '31012025'],
            ['E110', '180,00', '0,00', '0,00', '0,00', '0,00', '0,00', '0,00', '0,00', '0,00', '180,00', '0,00',
             '180,00', '0,00', '0,00'],
            ['E116', '000', '180,00', '20022025', '1015', '', '', '', '', '012025'],
        ],
        'G': [['G001', '1']],
        'H': [['H001', '1']],
        'K': [['K001', '1']],
        '1': [['1001', '0'], ['1010'] + ['N'] * 13],
    }
    linhas = []
    for bloco, regs in corpo.items():
        linhas += regs
        linhas.append([f'{bloco}990', str(len(regs) + 1)])
    contagem = {}
    for r in linhas:
        contagem[r[0]] = contagem.get(r[0], 0) + 1
    b9 = [['9001', '0']] + [['9900', reg, str(n)] for reg, n in contagem.items()]
    b9 += [['9900', '9001', '1'], ['9900', '9900', str(len(contagem) + 4)], ['9900', '9990', '1'],
           ['9900', '9999', '1']]
    b9.append(['9990', str(len(b9) + 2)])
    linhas += b9
    linhas.append(['9999', str(len(linhas) + 1)])
    return ''.join('|' + '|'.join(r) + '|\r\n' for r in linhas)


if __name__ == '__main__':
    aqui = Path(__file__).parent
    (aqui / 'efd-exemplo-valido.txt').write_bytes(montar().encode('iso-8859-1'))
    (aqui / 'efd-exemplo-com-erro.txt').write_bytes(montar(icms_c190='170,00').encode('iso-8859-1'))
    print('gerados: efd-exemplo-valido.txt, efd-exemplo-com-erro.txt')
