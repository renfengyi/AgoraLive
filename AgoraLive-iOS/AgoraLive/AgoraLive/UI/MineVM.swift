//
//  MineVM.swift
//  AgoraLive
//
//  Created by CavanSu on 2020/3/10.
//  Copyright © 2020 Agora. All rights reserved.
//

import UIKit
import RxSwift
import RxRelay

class MineVM: NSObject {
    private var abstraction: CurrentUser? {
        return ALCenter.shared().current
    }
    
    private let bag = DisposeBag()
    
    var userName = BehaviorRelay(value: "")
    var head = BehaviorRelay(value: UIImage())
    
    override init() {
        super.init()
        
        ALCenter.shared().isWorkNormally.subscribe(onNext: { [unowned self] (isWork) in
            guard isWork else {
                return
            }
            
            self.observe()
        }).disposed(by: bag)
    }
    
    func updateNewName(_ new: String, completion: Completion) {
        guard let abstraction = self.abstraction else {
            fatalError()
        }
        let info = CurrentUser.UpdateInfo(userName: new)
        abstraction.updateInfo(info, success: completion, fail: completion)
    }
}

private extension MineVM {
    func observe() {
        guard let abstraction = self.abstraction else {
            fatalError()
        }
        
        let images = ALCenter.shared().centerProvideImagesHelper()
        
        abstraction.publicInfo.subscribe(onNext: { [unowned self] (newInfo) in
            self.userName.accept(newInfo.name)
            let head = images.getHead(index: newInfo.imageIndex)
            self.head.accept(head)
        }).disposed(by: bag)
    }
}
