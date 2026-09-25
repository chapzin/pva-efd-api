#!/usr/bin/env python3
"""Gera dois arquivos EFD ICMS/IPI 100% fictícios para testar o validador.

  efd-exemplo-valido.txt  janeiro/2025, uma NF-e de saída com ICMS: deve passar.
  efd-exemplo-com-erro.txt  o mesmo arquivo com o ICMS do C190 diferente do C100.
  efd-exemplo-malha.txt  passa no PVA, mas credita ICMS de uso e consumo comprado
    de fornecedor do Simples: é o caso que /analisar e /cruzar apontam.
  xml-malha/  XMLs fictícios das notas desse período (uma delas não escriturada).

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
CNPJ_FORNECEDOR = dv_cnpj('778889990001')  # fornecedor fictício do Simples Nacional
CHAVE_COMPRA = dv_chave('23' + '2501' + CNPJ_FORNECEDOR + '55' + '001' + '000000456' + '1' + '87654321')
CHAVE_ESQUECIDA = dv_chave('23' + '2501' + CNPJ_FORNECEDOR + '55' + '001' + '000000457' + '1' + '87654322')


def montar(icms_c190='180,00', compra=False):
    credito, recolher = ('36,00', '144,00') if compra else ('0,00', '180,00')
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
        ] + ([['0150', 'F1', 'FORNECEDOR FICTICIO ME', '01058', CNPJ_FORNECEDOR, '', '', FORTALEZA, '',
               'RUA DO FORNECEDOR', '50', '', 'CENTRO'],
              ['0190', 'UN', 'UNIDADE'],
              ['0200', 'MAT01', 'MATERIAL DE ESCRITORIO', '', '', 'UN', '07', '48201000', '', '48', '', '', '']]
           if compra else []),
        'B': [['B001', '1']],
        'C': [
            ['C001', '0'],
            ['C100', '1', '0', 'C1', '55', '00', '001', '123', CHAVE, '15012025', '15012025', '1000,00', '0', '0,00',
             '0,00', '1000,00', '9', '0,00', '0,00', '0,00', '1000,00', '180,00', '0,00', '0,00', '0,00', '', '', '',
             ''],
            ['C190', '000', '5102', '18,00', '1000,00', '1000,00', icms_c190, '0,00', '0,00', '0,00', '0,00', ''],
        ] + ([
            ['C100', '0', '1', 'F1', '55', '00', '001', '456', CHAVE_COMPRA, '10012025', '12012025', '200,00', '0', '0,00',
             '0,00', '200,00', '9', '0,00', '0,00', '0,00', '200,00', '36,00', '0,00', '0,00', '0,00', '', '', '', ''],
            ['C170', '1', 'MAT01', '', '1', 'UN', '200,00', '0,00', '0', '000', '1556', '', '200,00', '18,00', '36,00',
             '0,00', '0,00', '0,00', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '0,00'],
            ['C190', '000', '1556', '18,00', '200,00', '200,00', '36,00', '0,00', '0,00', '0,00', '0,00', ''],
        ] if compra else []),
        'D': [['D001', '1']],
        'E': [
            ['E001', '0'],
            ['E100', '01012025', '31012025'],
            ['E110', '180,00', '0,00', '0,00', '0,00', credito, '0,00', '0,00', '0,00', '0,00', recolher, '0,00',
             recolher, '0,00', '0,00'],
            ['E116', '000', recolher, '20022025', '1015', '', '', '', '', '012025'],
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


def nfe(chave, emit, crt, dest, tp_nf, dia, v_nf, v_icms, v_cred_sn='0.00'):
    icms = (f'<ICMSSN101><orig>0</orig><CSOSN>101</CSOSN><pCredSN>1.00</pCredSN><vCredICMSSN>{v_cred_sn}</vCredICMSSN>'
            '</ICMSSN101>' if crt == '1' else
            f'<ICMS00><orig>0</orig><CST>00</CST><modBC>3</modBC><vBC>{v_nf}</vBC><pICMS>18.00</pICMS>'
            f'<vICMS>{v_icms}</vICMS></ICMS00>')
    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<nfeProc xmlns="http://www.portalfiscal.inf.br/nfe" versao="4.00"><NFe><infNFe Id="NFe' + chave + '" versao="4.00">'
            f'<ide><cUF>23</cUF><mod>55</mod><serie>1</serie><nNF>{int(chave[25:34])}</nNF><dhEmi>{dia}T10:00:00-03:00</dhEmi>'
            f'<tpNF>{tp_nf}</tpNF></ide><emit><CNPJ>{emit}</CNPJ><xNome>EMITENTE FICTICIO</xNome><CRT>{crt}</CRT></emit>'
            f'<dest><CNPJ>{dest}</CNPJ><xNome>DESTINATARIO FICTICIO</xNome></dest>'
            f'<det nItem="1"><prod><cProd>1</cProd><xProd>ITEM FICTICIO</xProd><vProd>{v_nf}</vProd></prod>'
            f'<imposto><ICMS>{icms}</ICMS></imposto></det>'
            f'<total><ICMSTot><vBC>{v_icms and v_nf}</vBC><vICMS>{v_icms}</vICMS><vNF>{v_nf}</vNF></ICMSTot></total>'
            '</infNFe></NFe><protNFe versao="4.00"><infProt><chNFe>' + chave + '</chNFe><cStat>100</cStat>'
            '<xMotivo>Autorizado o uso da NF-e</xMotivo></infProt></protNFe></nfeProc>\n')


if __name__ == '__main__':
    aqui = Path(__file__).parent
    (aqui / 'efd-exemplo-valido.txt').write_bytes(montar().encode('iso-8859-1'))
    (aqui / 'efd-exemplo-com-erro.txt').write_bytes(montar(icms_c190='170,00').encode('iso-8859-1'))
    (aqui / 'efd-exemplo-malha.txt').write_bytes(montar(compra=True).encode('iso-8859-1'))
    xml = aqui / 'xml-malha'
    xml.mkdir(exist_ok=True)
    (xml / f'{CHAVE}.xml').write_text(nfe(CHAVE, CNPJ, '3', CNPJ_CLIENTE, '1', '2025-01-15', '1000.00', '180.00'))
    (xml / f'{CHAVE_COMPRA}.xml').write_text(
        nfe(CHAVE_COMPRA, CNPJ_FORNECEDOR, '1', CNPJ, '1', '2025-01-10', '200.00', '0.00', v_cred_sn='2.00'))
    (xml / f'{CHAVE_ESQUECIDA}.xml').write_text(
        nfe(CHAVE_ESQUECIDA, CNPJ_FORNECEDOR, '1', CNPJ, '1', '2025-01-20', '350.00', '0.00', v_cred_sn='3.50'))
    print('gerados: efd-exemplo-valido.txt, efd-exemplo-com-erro.txt, efd-exemplo-malha.txt, xml-malha/')
