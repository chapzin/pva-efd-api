#!/usr/bin/env python3
"""Gera dois arquivos EFD ICMS/IPI 100% fictícios para testar o validador.

  efd-exemplo-valido.txt  janeiro/2025, uma NF-e de saída com ICMS: deve passar.
  efd-exemplo-com-erro.txt  o mesmo arquivo com o ICMS do C190 diferente do C100.
  efd-exemplo-malha.txt  passa no PVA, mas credita ICMS de uso e consumo comprado
    de fornecedor do Simples: é o caso que /analisar e /cruzar apontam.
  xml-malha/  XMLs fictícios das notas desse período (uma delas não escriturada).
  efd-exemplo-sat.txt + xml-sat/  vendas no SAT em resumo diário (C860/C890): cupom tributado
    resumido como ST, cupom fora da faixa do C860, dia sem resumo e um cupom cancelado.
  efd-exemplo-frete.txt + xml-frete/  fretes (D100 × CT-e): um tomado e escriturado, um creditado sem
    ser o tomador, um tomado e não escriturado e um cancelado (com o evento) fora da EFD.

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
CNPJ_TRANSPORTADORA = dv_cnpj('556667770001')  # transportadora fictícia
IE_TRANSPORTADORA = dv_ie_ce('06000003')


def chave_cte(n):
    return dv_chave('23' + '2501' + CNPJ_TRANSPORTADORA + '57' + '001' + f'{n:09d}' + '1' + f'{n:08d}')


CTE_TOMADO, CTE_ALHEIO, CTE_ESQUECIDO, CTE_CANCELADO = (chave_cte(n) for n in (801, 802, 803, 804))
CHAVE_ESQUECIDA = dv_chave('23' + '2501' + CNPJ_FORNECEDOR + '55' + '001' + '000000457' + '1' + '87654322')


NR_SAT = '900000001'
CNPJ_SOFTWARE_HOUSE = dv_cnpj('998887770001')  # software house fictícia
SIGN_AC = 'ZmljdGljaW8='
# Assinatura estrutural (não criptográfica): o suficiente para leitores que
# exigem o elemento, como a ingestão de acervos fiscais.
ASSINATURA = ('<Signature xmlns="http://www.w3.org/2000/09/xmldsig#"><SignedInfo/>'
              '<SignatureValue>ZmljdGljaW8=</SignatureValue><KeyInfo/></Signature>')


def chave_cfe(n):
    return dv_chave('23' + '2501' + CNPJ + '59' + NR_SAT + f'{n:06d}' + f'{n:06d}')


def montar(icms_c190='180,00', compra=False, sat=False, frete=False):
    credito, recolher = ('36,00', '144,00') if compra else ('0,00', '180,00')
    if frete:
        credito, recolher = '21,00', '159,00'
    corpo = {
        '0': [
            ['0000', '019', '0', '01012025', '31012025', 'EMPRESA FICTICIA DE EXEMPLO LTDA', CNPJ, '', 'CE', IE,
             FORTALEZA, '', '', 'B' if sat else 'A', '1'],
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
           if compra else []) + ([['0150', 'T1', 'TRANSPORTADORA FICTICIA LTDA', '01058', CNPJ_TRANSPORTADORA, '',
                                   IE_TRANSPORTADORA, FORTALEZA, '', 'RUA DA TRANSPORTADORA', '70', '', 'CENTRO']]
                                 if frete else []),
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
        ] if compra else []) + ([
            ['C860', '59', NR_SAT, '15012025', '1', '4'],
            ['C890', '060', '5102', '0,00', '100,00', '0,00', '0,00', ''],
            ['C890', '060', '5405', '0,00', '50,00', '0,00', '0,00', ''],
        ] if sat else []),
        'D': [['D001', '0'],
              ['D100', '0', '1', 'T1', '57', '00', '1', '', '801', CTE_TOMADO, '10012025', '10012025', '0', '', '100,00',
               '0,00', '0', '100,00', '100,00', '12,00', '0,00', '', '', FORTALEZA, FORTALEZA],
              ['D190', '000', '1353', '12,00', '100,00', '100,00', '12,00', '0,00', ''],
              ['D100', '0', '1', 'T1', '57', '00', '1', '', '802', CTE_ALHEIO, '12012025', '12012025', '0', '', '75,00',
               '0,00', '1', '75,00', '75,00', '9,00', '0,00', '', '', FORTALEZA, FORTALEZA],
              ['D190', '000', '1353', '12,00', '75,00', '75,00', '9,00', '0,00', '']]
        if frete else [['D001', '1']],
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


def nfe(chave, emit, crt, dest, tp_nf, dia, v_nf, v_icms, v_cred_sn='0.00', cfop='5102'):
    icms = (f'<ICMSSN101><orig>0</orig><CSOSN>101</CSOSN><pCredSN>1.00</pCredSN><vCredICMSSN>{v_cred_sn}</vCredICMSSN>'
            '</ICMSSN101>' if crt == '1' else
            f'<ICMS00><orig>0</orig><CST>00</CST><modBC>3</modBC><vBC>{v_nf}</vBC><pICMS>18.00</pICMS>'
            f'<vICMS>{v_icms}</vICMS></ICMS00>')
    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<nfeProc xmlns="http://www.portalfiscal.inf.br/nfe" versao="4.00"><NFe><infNFe Id="NFe' + chave + '" versao="4.00">'
            f'<ide><cUF>23</cUF><cNF>{chave[35:43]}</cNF><mod>55</mod><serie>1</serie><nNF>{int(chave[25:34])}</nNF>'
            f'<dhEmi>{dia}T10:00:00-03:00</dhEmi><tpNF>{tp_nf}</tpNF><tpEmis>{chave[34]}</tpEmis><cDV>{chave[43]}</cDV></ide><emit><CNPJ>{emit}</CNPJ><xNome>EMITENTE FICTICIO</xNome><CRT>{crt}</CRT></emit>'
            f'<dest><CNPJ>{dest}</CNPJ><xNome>DESTINATARIO FICTICIO</xNome></dest>'
            f'<det nItem="1"><prod><cProd>1</cProd><xProd>ITEM FICTICIO</xProd><CFOP>{cfop}</CFOP><vProd>{v_nf}</vProd></prod>'
            f'<imposto><ICMS>{icms}</ICMS></imposto></det>'
            f'<total><ICMSTot><vBC>{v_icms and v_nf}</vBC><vICMS>{v_icms}</vICMS><vNF>{v_nf}</vNF></ICMSTot></total>'
            '</infNFe>' + ASSINATURA + '</NFe><protNFe versao="4.00"><infProt><tpAmb>2</tpAmb><chNFe>' + chave + '</chNFe>'
            f'<dhRecbto>{dia}T10:00:05-03:00</dhRecbto><nProt>3232500000{chave[28:34]}</nProt><digVal>ZmljdGljaW8=</digVal>'
            '<cStat>100</cStat><xMotivo>Autorizado o uso da NF-e</xMotivo></infProt></protNFe></nfeProc>\n')


def cte(chave, toma, rem, dest, dia, v, v_icms):
    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<cteProc xmlns="http://www.portalfiscal.inf.br/cte" versao="4.00"><CTe xmlns="http://www.portalfiscal.inf.br/cte">'
            f'<infCte Id="CTe{chave}" versao="4.00"><ide><cUF>23</cUF><cCT>{chave[35:43]}</cCT><CFOP>5353</CFOP>'
            f'<natOp>PRESTACAO DE SERVICO DE TRANSPORTE</natOp><mod>57</mod><serie>1</serie><nCT>{int(chave[25:34])}</nCT>'
            f'<dhEmi>{dia}T10:00:00-03:00</dhEmi><tpImp>1</tpImp><tpEmis>{chave[34]}</tpEmis><cDV>{chave[43]}</cDV>'
            f'<tpAmb>2</tpAmb><tpCTe>0</tpCTe><procEmi>0</procEmi><verProc>1</verProc><cMunEnv>{FORTALEZA}</cMunEnv>'
            f'<xMunEnv>FORTALEZA</xMunEnv><UFEnv>CE</UFEnv><modal>01</modal><tpServ>0</tpServ><cMunIni>{FORTALEZA}</cMunIni>'
            f'<xMunIni>FORTALEZA</xMunIni><UFIni>CE</UFIni><cMunFim>{FORTALEZA}</cMunFim><xMunFim>FORTALEZA</xMunFim>'
            f'<UFFim>CE</UFFim><retira>1</retira><indIEToma>1</indIEToma><toma3><toma>{toma}</toma></toma3></ide>'
            f'<emit><CNPJ>{CNPJ_TRANSPORTADORA}</CNPJ><IE>{IE_TRANSPORTADORA}</IE><xNome>TRANSPORTADORA FICTICIA</xNome>'
            f'<CRT>3</CRT></emit><rem><CNPJ>{rem}</CNPJ><xNome>REMETENTE FICTICIO</xNome></rem>'
            f'<dest><CNPJ>{dest}</CNPJ><xNome>DESTINATARIO FICTICIO</xNome></dest>'
            f'<vPrest><vTPrest>{v}</vTPrest><vRec>{v}</vRec></vPrest><imp><ICMS><ICMS00><CST>00</CST><vBC>{v}</vBC>'
            f'<pICMS>12.00</pICMS><vICMS>{v_icms}</vICMS></ICMS00></ICMS></imp><infCTeNorm><infCarga><vCarga>1000.00</vCarga>'
            '</infCarga></infCTeNorm></infCte>' + ASSINATURA + '</CTe><protCTe versao="4.00"><infProt><tpAmb>2</tpAmb>'
            f'<chCTe>{chave}</chCTe><dhRecbto>{dia}T10:00:05-03:00</dhRecbto><nProt>3232500001{chave[28:34]}</nProt>'
            '<digVal>ZmljdGljaW8=</digVal><cStat>100</cStat><xMotivo>Autorizado o uso do CT-e</xMotivo></infProt></protCTe>'
            '</cteProc>\n')


def cte_canc(chave, dia):
    prot_ev = f'3232500002{chave[28:34]}'
    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<procEventoCTe xmlns="http://www.portalfiscal.inf.br/cte" versao="4.00"><eventoCTe versao="4.00">'
            f'<infEvento Id="ID110111{chave}01"><cOrgao>23</cOrgao><tpAmb>2</tpAmb><CNPJ>{CNPJ_TRANSPORTADORA}</CNPJ>'
            f'<chCTe>{chave}</chCTe><dhEvento>{dia}T11:00:00-03:00</dhEvento><tpEvento>110111</tpEvento>'
            '<nSeqEvento>1</nSeqEvento><detEvento versaoEvento="4.00"><evCancCTe><descEvento>Cancelamento</descEvento>'
            f'<nProt>3232500001{chave[28:34]}</nProt><xJust>FRETE NAO REALIZADO, EXEMPLO FICTICIO</xJust></evCancCTe>'
            '</detEvento></infEvento>' + ASSINATURA + '</eventoCTe><retEventoCTe versao="4.00">'
            f'<infEvento Id="ID{prot_ev}"><tpAmb>2</tpAmb><verAplic>1</verAplic><cOrgao>23</cOrgao><cStat>135</cStat>'
            f'<xMotivo>Evento registrado e vinculado a CT-e</xMotivo><chCTe>{chave}</chCTe><tpEvento>110111</tpEvento>'
            f'<xEvento>Cancelamento</xEvento><nSeqEvento>1</nSeqEvento><dhRegEvento>{dia}T11:00:05-03:00</dhRegEvento>'
            f'<nProt>{prot_ev}</nProt></infEvento></retEventoCTe></procEventoCTe>\n')


def cfe(n, dia, cfop, cst, v, v_icms):
    icms = (f'<ICMS00><Orig>0</Orig><CST>{cst}</CST><pICMS>18.00</pICMS><vICMS>{v_icms}</vICMS></ICMS00>' if cst == '00' else
            f'<ICMS40><Orig>0</Orig><CST>{cst}</CST></ICMS40>')
    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            f'<CFe><infCFe Id="CFe{chave_cfe(n)}" versao="0.08"><ide><cUF>23</cUF><mod>59</mod><nserieSAT>{NR_SAT}</nserieSAT>'
            f'<cNF>{n:06d}</cNF><nCFe>{n:06d}</nCFe><dEmi>2025{dia}</dEmi><hEmi>100000</hEmi><cDV>{chave_cfe(n)[43]}</cDV>'
            f'<tpAmb>2</tpAmb><CNPJ>{CNPJ_SOFTWARE_HOUSE}</CNPJ><signAC>{SIGN_AC}</signAC>'
            f'<assinaturaQRCODE>{SIGN_AC}</assinaturaQRCODE><numeroCaixa>001</numeroCaixa></ide><emit><CNPJ>{CNPJ}</CNPJ>'
            '<xNome>EMPRESA FICTICIA</xNome></emit><dest/>'
            f'<det nItem="1"><prod><cProd>1</cProd><xProd>ITEM FICTICIO</xProd><CFOP>{cfop}</CFOP><vItem>{v}</vItem></prod>'
            f'<imposto><ICMS>{icms}</ICMS></imposto></det><total><ICMSTot><vICMS>{v_icms}</vICMS></ICMSTot><vCFe>{v}</vCFe>'
            '</total></infCFe>' + ASSINATURA + '</CFe>\n')


def cfe_canc(n):
    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            f'<CFeCanc><infCFe Id="CFe{dv_chave(chave_cfe(n)[:31] + "999999999999")}" chCanc="CFe{chave_cfe(n)}">'
            f'<ide><cUF>23</cUF><mod>59</mod><CNPJ>{CNPJ_SOFTWARE_HOUSE}</CNPJ><signAC>{SIGN_AC}</signAC>'
            f'<numeroCaixa>001</numeroCaixa></ide><emit><CNPJ>{CNPJ}</CNPJ></emit></infCFe>' + ASSINATURA + '</CFeCanc>\n')


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
    (aqui / 'efd-exemplo-sat.txt').write_bytes(montar(sat=True).encode('iso-8859-1'))
    sat = aqui / 'xml-sat'
    sat.mkdir(exist_ok=True)
    for n, dia, cfop, cst, v, v_icms in [(1, '0115', '5102', '00', '100.00', '18.00'), (2, '0115', '5405', '60', '50.00', '0.00'),
                                         (3, '0115', '5102', '00', '10.00', '1.80'), (7, '0115', '5102', '00', '20.00', '3.60'),
                                         (5, '0116', '5102', '00', '30.00', '5.40')]:
        (sat / f'CFe{chave_cfe(n)}.xml').write_text(cfe(n, dia, cfop, cst, v, v_icms))
    (sat / f'CFeCanc{chave_cfe(3)}.xml').write_text(cfe_canc(3))
    (aqui / 'efd-exemplo-frete.txt').write_bytes(montar(frete=True).encode('iso-8859-1'))
    frete = aqui / 'xml-frete'
    frete.mkdir(exist_ok=True)
    # toma 0 = remetente, 3 = destinatário
    for chave, toma, rem, dest, dia, v, v_icms in [
            (CTE_TOMADO, '0', CNPJ, CNPJ_CLIENTE, '2025-01-10', '100.00', '12.00'),
            (CTE_ALHEIO, '0', CNPJ_FORNECEDOR, CNPJ, '2025-01-12', '75.00', '9.00'),
            (CTE_ESQUECIDO, '3', CNPJ_FORNECEDOR, CNPJ, '2025-01-20', '60.00', '7.20'),
            (CTE_CANCELADO, '0', CNPJ, CNPJ_CLIENTE, '2025-01-22', '40.00', '4.80')]:
        (frete / f'CTe{chave}.xml').write_text(cte(chave, toma, rem, dest, dia, v, v_icms))
    (frete / f'CTeCanc{CTE_CANCELADO}.xml').write_text(cte_canc(CTE_CANCELADO, '2025-01-22'))
    print('gerados: efd-exemplo-valido.txt, efd-exemplo-com-erro.txt, efd-exemplo-malha.txt, xml-malha/,'
          ' efd-exemplo-sat.txt, xml-sat/, efd-exemplo-frete.txt, xml-frete/')
