"""Offline real yt-dlp generic extraction and strict metadata/evidence boundaries; not Android execution."""
from copy import deepcopy
import importlib
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch


class SourceAnalysisTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.path=str(Path(__file__).parent.parent/'android_ytdlp');sys.path.insert(0,cls.path)
        cls.module=importlib.import_module('rewrite.experiments.android_native.source_analysis')
        cls.SpikeError=importlib.import_module('spike').SpikeError

    @classmethod
    def tearDownClass(cls):sys.path.remove(cls.path)

    def fixture(self):
        return dict(title='Owned',formats=[dict(format_id='real',url='https://owned.example.org/real.mp4?token=fixture-sensitive',protocol='https',ext='mp4',acodec='mp4a.40.2')])

    def test_real_pinned_generic_extractor_over_typed_metadata_preserves_unknowns(self):
        calls=[]
        class Error(Exception):pass
        def fetch(url,method):
            calls.append(method)
            html=b'''<html><title>Owned isolated source</title><video><source src='/real.mp4' type='video/mp4; codecs="avc1.42E01E, mp4a.40.2"'></video></html>'''
            return html,url,'text/plain',200,len(html)
        with patch.dict(sys.modules,{'_thekain_transport':types.SimpleNamespace(Error=Error,fetch_info=fetch)}):
            result=self.module.analyze('https://owned.example.org/page.html')
        self.assertEqual(result['status'],'PASS');self.assertEqual(calls,['GET'])
        self.assertFalse(result['download_enabled']);self.assertFalse(result['eligible'])
        self.assertEqual(result['analysis']['title'],'Owned isolated source (1)')
        f=result['analysis']['formats'][0];self.assertIsNone(f['video_codec']);self.assertEqual(f['audio_codec'],'mp4a.40.2')
        for key in ('bytes','width','height','fps'):self.assertIsNone(f[key])

    def test_metadata_denies_credentials_cross_origin_drm_manifests_and_fabricated_numbers(self):
        url='https://owned.example.org/page.html'
        for change in ('private','http','credentials','fragment','cookie','drm','live','manifest','audio_only','boolean_size','float_width','infinite_fps','id','codec','too_many','duplicate'):
            data=self.fixture();f=data['formats'][0]
            if change=='private':f['url']='https://127.0.0.1/secret.mp4'
            elif change=='http':f['url']='http://owned.example.org/real.mp4'
            elif change=='credentials':f['url']='https://user:secret@owned.example.org/real.mp4'
            elif change=='fragment':f['url']+='#secret'
            elif change=='cookie':f['http_headers']={'Cookie':'fixture-sensitive'}
            elif change=='drm':data['has_drm']=True
            elif change=='live':data['is_live']=True
            elif change=='manifest':f['manifest_url']='https://owned.example.org/index.m3u8'
            elif change=='audio_only':f['vcodec']='none'
            elif change=='boolean_size':f['filesize']=True
            elif change=='float_width':f['width']=1920.9
            elif change=='infinite_fps':f['fps']=float('inf')
            elif change=='id':f['format_id']='unsafe\nidentifier'
            elif change=='codec':f['acodec']='unsafe\ncodec'
            elif change=='too_many':data['formats']=[dict(f,format_id=str(i)) for i in range(9)]
            elif change=='duplicate':data['formats']=[f,deepcopy(f)]
            with self.subTest(change=change),self.assertRaises(self.SpikeError) as raised:self.module.normalize(data,url)
            self.assertNotIn('fixture-sensitive',str(raised.exception))
        data=self.fixture();f=self.module.normalize(data,url)['formats'][0]
        self.assertIsNone(f['video_codec']);self.assertIsNone(f['bytes'])

    def test_approved_transport_refuses_auth_headers_instead_of_forwarding_them(self):
        calls=[]
        class Error(Exception):pass
        def fetch(*args):calls.append(args);raise AssertionError('Must not reach native bridge')
        with patch.dict(sys.modules,{'_thekain_transport':types.SimpleNamespace(Error=Error,fetch_info=fetch)}):
            for header in ('Authorization','Cookie','Proxy-Authorization'):
                request=types.SimpleNamespace(url='https://owned.example.org/page',method='GET',headers={header:'fixture-sensitive'})
                with self.subTest(header=header),self.assertRaises(self.SpikeError):self.module.ApprovedNetwork().open(request)
        self.assertEqual(calls,[])
